package com.membershipflow.price.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.dto.LatestSourcePriceResponse;
import com.membershipflow.price.dto.PriceChartResponse;
import com.membershipflow.price.dto.PricePointDto;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceService {

    private final MembershipCourseRepository courseRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public List<LatestSourcePriceResponse> getLatestBySource(Long courseId) {
        if (!courseRepository.existsById(courseId)) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        return priceHistoryRepository.findLatestBySource(courseId).stream()
                .map(ph -> new LatestSourcePriceResponse(
                        ph.getSource().getName(),
                        ph.getSource().getBaseUrl(),
                        ph.getPrice(),
                        ph.getCollectedAt()))
                .toList();
    }

    public PriceChartResponse getChart(Long courseId, LocalDate from, LocalDate to,
                                       String interval, boolean isSubscriber) {
        MembershipCourse course = courseRepository.findById(courseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COURSE_NOT_FOUND));

        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
        }

        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        LocalDate requestedFrom = from != null ? from : effectiveTo.minusDays(30);

        // 비구독자: 최근 7일 clamp
        boolean subscriptionRequired = false;
        LocalDate effectiveFrom;
        if (!isSubscriber && requestedFrom.isBefore(effectiveTo.minusDays(7))) {
            effectiveFrom = effectiveTo.minusDays(7);
            subscriptionRequired = true;
        } else {
            effectiveFrom = requestedFrom;
        }

        LocalDateTime fromDt = effectiveFrom.atStartOfDay();
        LocalDateTime toDt   = effectiveTo.atTime(LocalTime.MAX);

        List<Object[]> rows = switch (interval.toUpperCase()) {
            case "WEEK"  -> priceHistoryRepository.findChartByWeek(courseId, fromDt, toDt);
            case "MONTH" -> priceHistoryRepository.findChartByMonth(courseId, fromDt, toDt);
            default      -> priceHistoryRepository.findChartByDay(courseId, fromDt, toDt);
        };

        List<PricePointDto> points = rows.stream()
                .map(this::toPoint)
                .toList();

        PriceChartResponse.Summary summary = buildSummary(points, courseId, fromDt, toDt);

        return new PriceChartResponse(
                courseId, course.getName(),
                interval.toUpperCase(),
                effectiveFrom, effectiveTo,
                points, summary, subscriptionRequired);
    }

    // 배치 조회: 목록용 latestPrice + priceChangeRate
    public Map<Long, PriceHistory> getLatestPriceBatch(List<Long> courseIds) {
        return priceHistoryRepository.findLatestByCourseIds(courseIds).stream()
                .collect(Collectors.toMap(ph -> ph.getCourse().getId(), ph -> ph));
    }

    public Map<Long, PriceHistory> get7dBasePriceBatch(List<Long> courseIds) {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime base   = now.minusDays(7);
        LocalDateTime window = base.minusDays(3);   // ±3일 fallback
        return priceHistoryRepository.findNearestToBatchByTime(courseIds, base, window, now).stream()
                .collect(Collectors.toMap(ph -> ph.getCourse().getId(), ph -> ph));
    }

    // 랭킹용 현재가/기준가 배치
    public Map<Long, PriceHistory> getCurrentPriceBatch(List<Long> courseIds) {
        return priceHistoryRepository.findCurrentPriceForRanking(courseIds).stream()
                .collect(Collectors.toMap(ph -> ph.getCourse().getId(), ph -> ph));
    }

    public List<Object[]> getLatestByTwoSources(String sourceA, String sourceB) {
        return priceHistoryRepository.findLatestByTwoSources(sourceA, sourceB);
    }

    // (courseId, sourceName, price) 행 목록 — 목록 거래소별 가격 표시용
    public List<Object[]> getLatestPerSourceRows(List<Long> courseIds) {
        return priceHistoryRepository.findLatestPerSourceByCourseIds(courseIds);
    }

    public long countCoursesUpdatedSince(LocalDateTime since) {
        return priceHistoryRepository.countCoursesUpdatedSince(since);
    }

    public Map<Long, PriceHistory> getBasePriceBatch(List<Long> courseIds, LocalDateTime baseTime) {
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(baseTime, LocalDateTime.now());
        long windowDays = Math.max(7, periodDays);
        LocalDateTime from = baseTime.minusDays(windowDays);
        LocalDateTime to   = baseTime.plusDays(windowDays);
        return priceHistoryRepository.findBasePriceForRanking(courseIds, baseTime, from, to).stream()
                .collect(Collectors.toMap(ph -> ph.getCourse().getId(), ph -> ph));
    }

    private PricePointDto toPoint(Object[] row) {
        LocalDate date     = ((java.sql.Date) row[0]).toLocalDate();
        long avgPrice      = ((BigDecimal) row[1]).longValue();
        long minPrice      = ((Number) row[2]).longValue();
        long maxPrice      = ((Number) row[3]).longValue();
        long count         = ((Number) row[4]).longValue();
        return new PricePointDto(date, avgPrice, minPrice, maxPrice, count);
    }

    private PriceChartResponse.Summary buildSummary(List<PricePointDto> points,
                                                     Long courseId,
                                                     LocalDateTime fromDt,
                                                     LocalDateTime toDt) {
        if (points.isEmpty()) {
            return new PriceChartResponse.Summary(null, null, null, null, null, null);
        }

        long currentPrice = points.get(points.size() - 1).avgPrice();
        long basePrice    = points.get(0).avgPrice();
        long minPrice     = points.stream().mapToLong(PricePointDto::minPrice).min().orElse(0);
        long maxPrice     = points.stream().mapToLong(PricePointDto::maxPrice).max().orElse(0);
        long changeAmount = currentPrice - basePrice;
        double changeRate = basePrice == 0 ? 0
                : Math.round((double) changeAmount / basePrice * 10000d) / 100d;

        return new PriceChartResponse.Summary(
                currentPrice, basePrice, changeAmount, changeRate, minPrice, maxPrice);
    }
}
