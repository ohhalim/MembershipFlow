package com.membershipflow.collect.service;

import com.membershipflow.collect.dto.AdminAlertResponse;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.price.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 크롤러 이상 탐지 (#159).
 *
 * <p>1) 수집량 급감 — 같은 소스의 직전 {@link CollectRun} 대비 successCount가
 * {@link #COLLECT_DROP_RATIO} 미만으로 떨어지면 이상 신호로 본다.
 *
 * <p>2) 거래소간 가격 이상치 — 3개 이상 소스에서 시세가 수집되는 코스에 한해
 * 소스 가격의 중앙값(median) 대비 {@link #PRICE_OUTLIER_DEVIATION}을 초과해
 * 벗어난 소스를 이상치로 본다. (2개뿐이면 어느 쪽이 이상인지 판단 불가하므로 제외)
 *
 * <p>탐지 결과는 WARN 로그로 남기고, {@code MemberRole.ADMIN} 회원에게
 * WebSocket({@code /queue/admin-alert})으로 알림을 보낸다. 알림 발송 자체가
 * 실패해도 수집 파이프라인에는 영향을 주지 않도록 예외를 격리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    // 이전 대비 이 비율 미만으로 successCount가 떨어지면 급감으로 판단
    private static final double COLLECT_DROP_RATIO = 0.5;
    // median 대비 이 비율을 초과해 벗어나면 이상치로 판단
    private static final double PRICE_OUTLIER_DEVIATION = 0.5;
    // 이 개수 미만의 소스만 있는 코스는 median 비교 대상에서 제외
    private static final int MIN_SOURCES_FOR_OUTLIER_CHECK = 3;

    private final CollectRunRepository       collectRunRepository;
    private final MembershipCourseRepository membershipCourseRepository;
    private final PriceService               priceService;
    private final MemberRepository           memberRepository;
    private final SimpMessagingTemplate      messagingTemplate;

    /** 같은 소스의 직전 CollectRun과 비교해 successCount 급감 여부를 확인한다. */
    @Transactional(readOnly = true)
    public void checkCollectDrop(CrawlSource source, CollectRun currentRun) {
        collectRunRepository
                .findTopBySourceAndIdLessThanOrderByIdDesc(source, currentRun.getId())
                .ifPresent(previousRun -> evaluateCollectDrop(source, previousRun, currentRun));
    }

    private void evaluateCollectDrop(CrawlSource source, CollectRun previousRun, CollectRun currentRun) {
        int previousCount = previousRun.getSuccessCount();
        int currentCount  = currentRun.getSuccessCount();
        if (previousCount <= 0) return;
        if (currentCount >= previousCount * COLLECT_DROP_RATIO) return;

        String detail = String.format("이전=%d건 → 이번=%d건", previousCount, currentCount);
        log.warn("[이상탐지] 수집량 급감: source={} {}", source.getName(), detail);
        notifyAdmins(new AdminAlertResponse(
                "COLLECT_DROP",
                source.getName() + " 수집량 급감",
                null, null, detail));
    }

    /** 전체 소스 수집 완료 후 코스별 거래소간 가격 이상치를 탐지한다. */
    @Transactional(readOnly = true)
    public void checkPriceOutliers() {
        List<MembershipCourse> activeCourses = membershipCourseRepository.findAllByActiveTrue();
        if (activeCourses.isEmpty()) return;

        List<Long> courseIds = activeCourses.stream().map(MembershipCourse::getId).toList();
        Map<Long, MembershipCourse> courseMap = activeCourses.stream()
                .collect(Collectors.toMap(MembershipCourse::getId, c -> c));

        Map<Long, List<SourcePrice>> pricesByCourse = priceService.getLatestPerSourceRows(courseIds).stream()
                .collect(Collectors.groupingBy(
                        row -> ((Number) row[0]).longValue(),
                        Collectors.mapping(
                                row -> new SourcePrice((String) row[1], ((Number) row[2]).longValue()),
                                Collectors.toList())));

        pricesByCourse.forEach((courseId, prices) -> {
            MembershipCourse course = courseMap.get(courseId);
            if (course != null) {
                evaluatePriceOutliers(course, prices);
            }
        });
    }

    private void evaluatePriceOutliers(MembershipCourse course, List<SourcePrice> prices) {
        if (prices.size() < MIN_SOURCES_FOR_OUTLIER_CHECK) return;

        double median = median(prices.stream().map(SourcePrice::price).toList());
        if (median <= 0) return;

        for (SourcePrice sp : prices) {
            double deviation = (sp.price() - median) / median;
            if (Math.abs(deviation) <= PRICE_OUTLIER_DEVIATION) continue;

            String detail = String.format(
                    "median=%.0f원, %s=%d원 (%+.1f%%)",
                    median, sp.source(), sp.price(), deviation * 100);
            log.warn("[이상탐지] 가격 이상치: courseId={} courseName={} {}",
                    course.getId(), course.getName(), detail);
            notifyAdmins(new AdminAlertResponse(
                    "PRICE_OUTLIER",
                    course.getName() + " 가격 이상치 (" + sp.source() + ")",
                    course.getId(), course.getName(), detail));
        }
    }

    // 표준 median 정의: 홀수 개는 가운데 값, 짝수 개는 가운데 두 값의 평균
    private double median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int n   = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    // 알림 발송 실패가 수집 자체를 실패시키지 않도록 격리
    private void notifyAdmins(AdminAlertResponse payload) {
        try {
            List<Member> admins = memberRepository.findAllByRole(MemberRole.ADMIN);
            if (admins.isEmpty()) {
                log.debug("[이상탐지] 관리자 없음 - 알림 생략");
                return;
            }
            for (Member admin : admins) {
                messagingTemplate.convertAndSendToUser(
                        admin.getId().toString(), "/queue/admin-alert", payload);
            }
        } catch (Exception e) {
            log.error("[이상탐지] 관리자 알림 발송 실패: {}", e.getMessage(), e);
        }
    }

    private record SourcePrice(String source, long price) {}
}
