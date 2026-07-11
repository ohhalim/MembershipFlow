package com.membershipflow.subscription.scheduler;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService    subscriptionService;
    private final MeterRegistry          meterRegistry;

    // 배치 하트비트 (#188): processDueBillings()가 끝까지 실행 완료된 시각(epoch seconds).
    // be#178(트랜잭션 없는 락 쿼리로 배치가 매일 즉사)이 재발하면 이 값이 멈춘다
    private final AtomicLong billingLastRunTimestamp = new AtomicLong(0);

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("billing_last_run_timestamp_seconds", billingLastRunTimestamp, AtomicLong::get)
                .description("마지막으로 processDueBillings()가 끝까지 실행 완료된 시각(epoch seconds)")
                .register(meterRegistry);
    }

    /** 매일 자정 — 결제일이 도래한 구독 일괄 청구 */
    @Scheduled(cron = "0 0 0 * * *")
    public void processDueBillings() {
        List<Subscription> dueList = subscriptionRepository.findDueForBilling(
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAYMENT_FAILED),
                LocalDateTime.now());

        log.info("정기결제 대상: {}건", dueList.size());

        for (Subscription sub : dueList) {
            try {
                subscriptionService.processBilling(sub.getId());
            } catch (Exception e) {
                log.error("정기결제 처리 중 예외: subscriptionId={}", sub.getId(), e);
            }
        }

        billingLastRunTimestamp.set(Instant.now().getEpochSecond());
    }
}
