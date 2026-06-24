package com.membershipflow.subscription.scheduler;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService    subscriptionService;

    /** 매일 자정 — 결제일이 도래한 구독 일괄 청구 */
    @Scheduled(cron = "0 0 0 * * *")
    public void processDueBillings() {
        List<Subscription> dueList = subscriptionRepository.findDueForBillingWithLock(
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
    }
}
