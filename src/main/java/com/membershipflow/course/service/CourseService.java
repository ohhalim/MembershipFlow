package com.membershipflow.course.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.course.dto.CourseDetailResponse;
import com.membershipflow.course.dto.CourseListItemResponse;
import com.membershipflow.course.dto.MarketSummaryResponse;
import com.membershipflow.course.dto.RankingItemResponse;
import com.membershipflow.course.dto.RankingPageResponse;
import com.membershipflow.course.dto.SourceComparisonItem;
import com.membershipflow.course.entity.CourseInfo;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.CourseInfoRepository;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final MembershipCourseRepository courseRepository;
    private final CourseInfoRepository courseInfoRepository;
    private final PriceService priceService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Page<CourseListItemResponse> search(String q, CourseType courseType,
                                                MembershipType membershipType, String region,
                                                String sort, Pageable pageable) {
        boolean priceSort = "price_asc".equals(sort) || "price_desc".equals(sort) || "latest".equals(sort);

        if (priceSort) {
            return searchWithPriceSort(q, courseType, membershipType, region, sort, pageable);
        }

        Page<MembershipCourse> page = courseRepository.search(q, courseType, membershipType, region, pageable);
        List<Long> ids = page.map(MembershipCourse::getId).toList();

        Map<Long, PriceHistory> latestMap = ids.isEmpty() ? Map.of()
                : priceService.getLatestPriceBatch(ids);
        Map<Long, PriceHistory> baseMap   = ids.isEmpty() ? Map.of()
                : priceService.get7dBasePriceBatch(ids);
        Map<Long, List<CourseListItemResponse.SourcePriceItem>> sourcePriceMap = getSourcePriceMap(ids);

        List<CourseListItemResponse> content = page.getContent().stream()
                .map(c -> toCourseListItem(c, latestMap, baseMap, sourcePriceMap))
                .toList();

        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    private Page<CourseListItemResponse> searchWithPriceSort(String q, CourseType courseType,
                                                              MembershipType membershipType, String region,
                                                              String sort, Pageable pageable) {
        String courseTypeStr     = courseType     != null ? courseType.name()     : null;
        String membershipTypeStr = membershipType != null ? membershipType.name() : null;

        List<MembershipCourse> paged = courseRepository.searchWithPriceSort(
                q, courseTypeStr, membershipTypeStr, region, sort,
                pageable.getPageSize(), pageable.getOffset());

        long total = courseRepository.countSearch(q, courseTypeStr, membershipTypeStr, region);

        List<Long> ids = paged.stream().map(MembershipCourse::getId).toList();
        // (#100) price_history 재조회 없이 searchWithPriceSort가 이미 가져온 비정규화 컬럼을 그대로 사용
        Map<Long, PriceHistory> latestMap = paged.stream()
                .filter(c -> c.getLatestPrice() != null)
                .collect(Collectors.toMap(MembershipCourse::getId,
                        c -> PriceHistory.builder()
                                .price(c.getLatestPrice())
                                .collectedAt(c.getLatestPriceAt())
                                .build()));
        Map<Long, PriceHistory> baseMap   = ids.isEmpty() ? Map.of() : priceService.get7dBasePriceBatch(ids);
        Map<Long, List<CourseListItemResponse.SourcePriceItem>> sourcePriceMap = getSourcePriceMap(ids);

        List<CourseListItemResponse> content = paged.stream()
                .map(c -> toCourseListItem(c, latestMap, baseMap, sourcePriceMap))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // (courseId, sourceName, price, collectedAt) 행을 courseId별로 그룹핑.
    // PriceHistoryRepository가 이미 활성·fresh 소스만 반환하므로 stale 행은 숨긴다.
    private Map<Long, List<CourseListItemResponse.SourcePriceItem>> getSourcePriceMap(List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return priceService.getLatestPerSourceRows(ids).stream()
                .collect(Collectors.groupingBy(
                        row -> ((Number) row[0]).longValue(),
                        Collectors.mapping(
                                row -> new CourseListItemResponse.SourcePriceItem(
                                        (String) row[1], ((Number) row[2]).longValue(),
                                        row.length > 3 ? formatCollectedAt(row[3]) : null,
                                        true),
                                Collectors.toList())));
    }

    private CourseListItemResponse toCourseListItem(MembershipCourse c,
                                                     Map<Long, PriceHistory> latestMap,
                                                     Map<Long, PriceHistory> baseMap,
                                                     Map<Long, List<CourseListItemResponse.SourcePriceItem>> sourcePriceMap) {
        PriceHistory latest = latestMap.get(c.getId());
        PriceHistory base   = baseMap.get(c.getId());
        List<CourseListItemResponse.SourcePriceItem> sourcePrices =
                sourcePriceMap.getOrDefault(c.getId(), List.of());
        CourseListItemResponse.SourcePriceItem representative = resolveRepresentativeSourcePrice(sourcePrices);
        Long latestPrice = representative != null ? representative.price()
                : latest != null ? latest.getPrice() : null;
        String updatedAt = representative != null && representative.collectedAt() != null
                ? representative.collectedAt()
                : latest != null ? latest.getCollectedAt().toString() : null;
        String latestPriceSource = representative != null ? representative.source()
                : latest != null && latest.getSource() != null
                ? latest.getSource().getName()
                : c.getLatestPriceSource();
        PriceHistory representativeHistory = representative != null
                ? toPriceHistory(representative, latest)
                : latest;
        return new CourseListItemResponse(
                c.getId(), c.getName(), c.getRegion(),
                c.getCourseType() != null ? c.getCourseType().name() : null,
                c.getMembershipType() != null ? c.getMembershipType().name() : null,
                c.getHoles(),
                latestPrice,
                updatedAt,
                calcChangeRate(representativeHistory, base),
                latestPriceSource,
                sourcePrices);
    }

    // 목록 대표 가격 규칙 (#262): 활성·fresh 거래소별 최신가 중 최저가를 대표 가격으로 사용.
    // sourcePrices가 비어 있는 경우에는 DB 대표 행(latest)도 이미 같은 freshness 계약을 따른다.
    private CourseListItemResponse.SourcePriceItem resolveRepresentativeSourcePrice(
            List<CourseListItemResponse.SourcePriceItem> sourcePrices) {
        return sourcePrices.stream()
                .filter(CourseListItemResponse.SourcePriceItem::fresh)
                .filter(p -> p.price() != null)
                .min(java.util.Comparator.comparing(CourseListItemResponse.SourcePriceItem::price)
                        .thenComparing(p -> p.collectedAt() == null ? "" : p.collectedAt()))
                .orElse(null);
    }

    private String formatCollectedAt(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString();
        }
        return value.toString();
    }

    private PriceHistory toPriceHistory(CourseListItemResponse.SourcePriceItem sourcePrice,
                                        PriceHistory fallback) {
        LocalDateTime collectedAt = fallback != null ? fallback.getCollectedAt() : null;
        if (sourcePrice.collectedAt() != null) {
            try {
                collectedAt = LocalDateTime.parse(sourcePrice.collectedAt());
            } catch (java.time.format.DateTimeParseException ignored) {
                // 테스트/구버전 응답에 시각이 없거나 파싱할 수 없으면 단건 latest를 사용한다.
            }
        }
        return PriceHistory.builder()
                .price(sourcePrice.price())
                .collectedAt(collectedAt)
                .build();
    }

    public CourseDetailResponse getDetail(Long courseId) {
        MembershipCourse course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_NOT_FOUND));

        List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices =
                priceService.getLatestBySource(courseId);

        Long latestPrice  = resolveDetailPrice(rawPrices);
        String updatedAt  = resolveDetailUpdatedAt(rawPrices, latestPrice);
        String latestPriceSource = rawPrices.stream()
                .filter(p -> p.fresh())
                .filter(p -> latestPrice != null && latestPrice.equals(p.price()))
                .map(com.membershipflow.price.dto.LatestSourcePriceResponse::sourceName)
                .findFirst()
                .orElse(null);
        PriceHistory base = priceService.get7dBasePriceBatch(List.of(courseId)).get(courseId);
        Double changeRate = latestPrice != null
                ? calcChangeRate(PriceHistory.builder().price(latestPrice).build(), base)
                : null;

        return CourseDetailResponse.of(
                course.getId(), course.getName(), course.getRegion(),
                course.getCourseType(), course.getMembershipType(), course.getHoles(),
                rawPrices, latestPrice, updatedAt, changeRate,
                latestPriceSource,
                false, null,
                courseInfoRepository.findByCourseId(courseId)
                        .map(this::toCourseInfoDto)
                        .orElse(null));
    }

    // 상세 대표 가격 (#262): 목록과 동일한 규칙 — 활성·fresh 거래소별 최신가 중 최저가.
    // 유효한 거래소별 가격이 없으면 오래된 비정규화 컬럼으로 폴백하지 않는다.
    private Long resolveDetailPrice(List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices) {
        return rawPrices.stream()
                .filter(com.membershipflow.price.dto.LatestSourcePriceResponse::fresh)
                .map(com.membershipflow.price.dto.LatestSourcePriceResponse::price)
                .filter(Objects::nonNull)
                .min(Long::compareTo)
                .orElse(null);
    }

    // 상세 updatedAt: 대표 가격으로 선택된 동일 행의 수집 시각.
    private String resolveDetailUpdatedAt(List<com.membershipflow.price.dto.LatestSourcePriceResponse> rawPrices,
                                           Long latestPrice) {
        return rawPrices.stream()
                .filter(com.membershipflow.price.dto.LatestSourcePriceResponse::fresh)
                .filter(p -> latestPrice != null && latestPrice.equals(p.price()))
                .map(p -> p.collectedAt() != null ? p.collectedAt().toString() : null)
                .findFirst()
                .orElse(null);
    }

    private CourseDetailResponse.CourseInfoDto toCourseInfoDto(CourseInfo info) {
        return new CourseDetailResponse.CourseInfoDto(
                info.getAddress(),
                info.getMembershipIntro(),
                info.getCourseIntro(),
                info.getPriceOutlook(),
                deserializeGreenFees(info.getGreenFees()),
                info.getCaddieFee(),
                info.getCartFee());
    }

    // DB에 JSON 문자열로 저장된 그린피를 역직렬화 — 파싱 실패 시 null
    private List<CourseDetailResponse.CourseInfoDto.GreenFeeDto> deserializeGreenFees(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    public RankingPageResponse getRanking(String period, String sort,
                                          CourseType courseType, int page, int size) {
        LocalDateTime baseTime = LocalDateTime.now().minus(parsePeriod(period));
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(baseTime, LocalDateTime.now());
        long windowDays = Math.max(7, periodDays);
        LocalDateTime searchFrom = baseTime.minusDays(windowDays);
        LocalDateTime searchTo = baseTime.plusDays(windowDays);
        String courseTypeName = courseType != null ? courseType.name() : null;
        String normalizedSort = "LOSS".equalsIgnoreCase(sort) ? "LOSS" : "GAIN";
        long offset = (long) page * size;

        List<Object[]> rows = courseRepository.findRankingPage(
                baseTime, searchFrom, searchTo, courseTypeName, normalizedSort, size, offset);
        long totalElements = courseRepository.countRanking(
                baseTime, searchFrom, searchTo, courseTypeName, normalizedSort);

        List<RankingItemResponse> content = java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> toRankingItem(rows.get(index), offset + index + 1))
                .toList();
        return new RankingPageResponse(
                content, page, size, totalElements, offset + content.size() < totalElements);
    }

    private RankingItemResponse toRankingItem(Object[] row, long rank) {
        return new RankingItemResponse(
                Math.toIntExact(rank),
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                CourseType.valueOf((String) row[3]),
                MembershipType.valueOf((String) row[4]),
                ((Number) row[5]).longValue(),
                ((Number) row[6]).longValue(),
                ((Number) row[7]).doubleValue(),
                ((Number) row[8]).longValue());
    }

    // 시장 요약: 오늘 갱신 종목 수 + 1일 기준 상승/하락 종목 수 + 거래소 스프레드 지표
    public MarketSummaryResponse getSummary() {
        List<MembershipCourse> all = courseRepository.findAllByActiveTrue();
        if (all.isEmpty()) return new MarketSummaryResponse(0, 0, 0, 0, 0.0);

        long updatedToday = priceService.countCoursesUpdatedSince(
                java.time.LocalDate.now().atStartOfDay());

        List<Long> ids = all.stream().map(MembershipCourse::getId).toList();
        Map<Long, PriceHistory> currentMap = priceService.getCurrentPriceBatch(ids);
        Map<Long, PriceHistory> baseMap    = priceService.getBasePriceBatch(ids, LocalDateTime.now().minusDays(1));

        int risers = 0, fallers = 0;
        for (Long id : ids) {
            PriceHistory current = currentMap.get(id);
            PriceHistory base    = baseMap.get(id);
            if (current == null || base == null || base.getPrice() == 0) continue;
            long diff = current.getPrice() - base.getPrice();
            if (diff > 0) risers++;
            else if (diff < 0) fallers++;
        }

        // 거래소 스프레드: 소스 2개 이상인 활성 종목 수 + 최대 가격차율(%)
        // getSourceComparison과 동일하게 코스별 소스별 가격을 그룹핑해 재사용
        Map<Long, List<Long>> pricesByCourse = priceService.getLatestPerSourceRows(ids).stream()
                .collect(Collectors.groupingBy(
                        row -> ((Number) row[0]).longValue(),
                        Collectors.mapping(
                                row -> ((Number) row[2]).longValue(),
                                Collectors.toList())));

        int comparedCourses = 0;
        double maxSpreadRate = 0.0;
        for (List<Long> prices : pricesByCourse.values()) {
            if (prices.size() < 2) continue;
            comparedCourses++;
            long minPrice = prices.stream().mapToLong(Long::longValue).min().orElse(0);
            long maxPrice = prices.stream().mapToLong(Long::longValue).max().orElse(0);
            if (minPrice <= 0) continue;
            double spreadRate = Math.round((double) (maxPrice - minPrice) / minPrice * 10000d) / 100d;
            if (spreadRate > maxSpreadRate) maxSpreadRate = spreadRate;
        }

        return new MarketSummaryResponse(updatedToday, risers, fallers, comparedCourses, maxSpreadRate);
    }

    // 거래소 간 가격 비교: 소스가 2개 이상인 활성 코스만 대상, diffRate 절대값 내림차순 상위 limit개
    public List<SourceComparisonItem> getSourceComparison(int limit) {
        List<MembershipCourse> activeCourses = courseRepository.findAllByActiveTrue();
        if (activeCourses.isEmpty()) return List.of();

        List<Long> courseIds = activeCourses.stream().map(MembershipCourse::getId).toList();
        Map<Long, MembershipCourse> courseMap = activeCourses.stream()
                .collect(Collectors.toMap(MembershipCourse::getId, c -> c));

        Map<Long, List<SourceComparisonItem.SourcePricePoint>> pricesByCourse =
                priceService.getLatestPerSourceRows(courseIds).stream()
                        .collect(Collectors.groupingBy(
                                row -> ((Number) row[0]).longValue(),
                                Collectors.mapping(
                                        row -> new SourceComparisonItem.SourcePricePoint(
                                                (String) row[1], ((Number) row[2]).longValue()),
                                        Collectors.toList())));

        return pricesByCourse.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)
                .map(e -> {
                    Long courseId = e.getKey();
                    MembershipCourse c = courseMap.get(courseId);
                    if (c == null) return null;

                    List<SourceComparisonItem.SourcePricePoint> prices = e.getValue();
                    long minPrice = prices.stream()
                            .mapToLong(SourceComparisonItem.SourcePricePoint::price).min().orElse(0);
                    long maxPrice = prices.stream()
                            .mapToLong(SourceComparisonItem.SourcePricePoint::price).max().orElse(0);
                    long diffAmount = maxPrice - minPrice;
                    double diffRate = minPrice == 0 ? 0
                            : Math.round((double) diffAmount / minPrice * 10000d) / 100d;

                    return new SourceComparisonItem(
                            courseId, c.getName(), c.getRegion(),
                            c.getCourseType() != null ? c.getCourseType().name() : null,
                            prices, minPrice, maxPrice, diffAmount, diffRate);
                })
                .filter(Objects::nonNull)
                .sorted((a, b) -> Double.compare(Math.abs(b.diffRate()), Math.abs(a.diffRate())))
                .limit(limit)
                .toList();
    }

    private Double calcChangeRate(PriceHistory latest, PriceHistory base) {
        if (latest == null || base == null || base.getPrice() == 0) return null;
        double rate = (double) (latest.getPrice() - base.getPrice()) / base.getPrice() * 100;
        return Math.round(rate * 100d) / 100d;
    }

    private java.time.temporal.TemporalAmount parsePeriod(String period) {
        return switch (period) {
            case "1d"  -> java.time.Duration.ofDays(1);
            case "30d" -> java.time.Duration.ofDays(30);
            case "90d" -> java.time.Duration.ofDays(90);
            default    -> java.time.Duration.ofDays(7);   // 7d
        };
    }
}
