package com.membershipflow.course.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.course.dto.CourseDetailResponse;
import com.membershipflow.course.dto.CourseListItemResponse;
import com.membershipflow.course.dto.RankingItemResponse;
import com.membershipflow.course.dto.RankingPageResponse;
import com.membershipflow.course.dto.SourceComparisonItem;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final MembershipCourseRepository courseRepository;
    private final PriceService priceService;

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

        List<CourseListItemResponse> content = page.getContent().stream()
                .map(c -> toCourseListItem(c, latestMap, baseMap))
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
        Map<Long, PriceHistory> latestMap = ids.isEmpty() ? Map.of() : priceService.getLatestPriceBatch(ids);
        Map<Long, PriceHistory> baseMap   = ids.isEmpty() ? Map.of() : priceService.get7dBasePriceBatch(ids);

        List<CourseListItemResponse> content = paged.stream()
                .map(c -> toCourseListItem(c, latestMap, baseMap))
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    private CourseListItemResponse toCourseListItem(MembershipCourse c,
                                                     Map<Long, PriceHistory> latestMap,
                                                     Map<Long, PriceHistory> baseMap) {
        PriceHistory latest = latestMap.get(c.getId());
        PriceHistory base   = baseMap.get(c.getId());
        return new CourseListItemResponse(
                c.getId(), c.getName(), c.getRegion(),
                c.getCourseType() != null ? c.getCourseType().name() : null,
                c.getMembershipType() != null ? c.getMembershipType().name() : null,
                c.getHoles(),
                latest != null ? latest.getPrice() : null,
                latest != null ? latest.getCollectedAt().toString() : null,
                calcChangeRate(latest, base));
    }

    public CourseDetailResponse getDetail(Long courseId) {
        MembershipCourse course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_NOT_FOUND));

        return CourseDetailResponse.of(
                course.getId(), course.getName(), course.getRegion(),
                course.getCourseType(), course.getMembershipType(), course.getHoles(),
                priceService.getLatestBySource(courseId),
                false, null);
    }

    public RankingPageResponse getRanking(String period, String sort,
                                          CourseType courseType, int page, int size) {
        List<MembershipCourse> all = courseType != null
                ? courseRepository.findAll().stream()
                        .filter(c -> c.getCourseType() == courseType && c.isActive())
                        .toList()
                : courseRepository.findAll().stream()
                        .filter(MembershipCourse::isActive)
                        .toList();

        if (all.isEmpty()) return new RankingPageResponse(List.of(), page, size, 0, false);

        List<Long> ids = all.stream().map(MembershipCourse::getId).toList();
        LocalDateTime baseTime = LocalDateTime.now().minus(parsePeriod(period));

        Map<Long, PriceHistory> currentMap = priceService.getCurrentPriceBatch(ids);
        Map<Long, PriceHistory> baseMap    = priceService.getBasePriceBatch(ids, baseTime);
        Map<Long, MembershipCourse> courseMap = all.stream()
                .collect(Collectors.toMap(MembershipCourse::getId, c -> c));

        List<RankingItemResponse> ranked = new ArrayList<>();
        for (Long id : ids) {
            PriceHistory current = currentMap.get(id);
            PriceHistory base    = baseMap.get(id);
            if (current == null || base == null || base.getPrice() == 0) continue;

            long currentPrice = current.getPrice();
            long basePrice    = base.getPrice();
            long changeAmount = currentPrice - basePrice;
            double changeRate = Math.round((double) changeAmount / basePrice * 10000d) / 100d;

            MembershipCourse c = courseMap.get(id);
            ranked.add(new RankingItemResponse(
                    0, id, c.getName(), c.getRegion(),
                    c.getCourseType(), c.getMembershipType(),
                    currentPrice, basePrice, changeRate, changeAmount));
        }

        // 실제 변동이 있는 항목만 포함 (0.0% 제외)
        boolean isLoss = "LOSS".equalsIgnoreCase(sort);
        ranked = ranked.stream()
                .filter(r -> isLoss ? r.changeRate() < 0 : r.changeRate() > 0)
                .collect(Collectors.toCollection(ArrayList::new));

        // GAIN: 상승률 내림차순, LOSS: 하락률 오름차순
        ranked.sort(isLoss
                ? (a, b) -> Double.compare(a.changeRate(), b.changeRate())
                : (a, b) -> Double.compare(b.changeRate(), a.changeRate()));

        // rank 번호 부여 + 페이지 슬라이싱
        long totalElements = ranked.size();
        int fromIndex = page * size;
        if (fromIndex >= totalElements) return new RankingPageResponse(List.of(), page, size, totalElements, false);

        int toIndex = (int) Math.min(fromIndex + size, totalElements);
        List<RankingItemResponse> content = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; i++) {
            RankingItemResponse r = ranked.get(i);
            content.add(new RankingItemResponse(i + 1, r.courseId(), r.name(), r.region(),
                    r.courseType(), r.membershipType(), r.currentPrice(), r.basePrice(),
                    r.changeRate(), r.changeAmount()));
        }
        return new RankingPageResponse(content, page, size, totalElements, toIndex < totalElements);
    }

    public List<SourceComparisonItem> getSourceComparison(int limit) {
        List<Object[]> rows = priceService.getLatestByTwoSources("동아골프", "동부회원권");

        List<Long> courseIds = rows.stream()
                .map(r -> ((Number) r[0]).longValue())
                .toList();

        Map<Long, MembershipCourse> courseMap = courseRepository.findAllById(courseIds).stream()
                .collect(Collectors.toMap(MembershipCourse::getId, c -> c));

        return rows.stream()
                .map(r -> {
                    long courseId  = ((Number) r[0]).longValue();
                    long dongaPrice = ((Number) r[1]).longValue();
                    long dongbuPrice = ((Number) r[2]).longValue();
                    long diffAmount  = dongbuPrice - dongaPrice;
                    double diffRate  = dongaPrice == 0 ? 0
                            : Math.round((double) diffAmount / dongaPrice * 10000d) / 100d;

                    MembershipCourse c = courseMap.get(courseId);
                    if (c == null) return null;
                    return new SourceComparisonItem(
                            courseId, c.getName(), c.getRegion(),
                            c.getCourseType() != null ? c.getCourseType().name() : null,
                            dongaPrice, dongbuPrice, diffAmount, diffRate);
                })
                .filter(item -> item != null)
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
