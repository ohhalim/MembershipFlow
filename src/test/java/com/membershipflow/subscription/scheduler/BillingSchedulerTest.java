package com.membershipflow.subscription.scheduler;

import com.membershipflow.common.monitoring.BatchHeartbeatService;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @Mock SubscriptionService    subscriptionService;
    @Mock BatchHeartbeatService  batchHeartbeatService;

    SimpleMeterRegistry meterRegistry;
    BillingScheduler billingScheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        billingScheduler = new BillingScheduler(
                subscriptionRepository, subscriptionService, meterRegistry, batchHeartbeatService);
        billingScheduler.registerMetrics();
    }

    @Test
    @DisplayName("결제일이 도래한 구독 각각에 대해 processBilling을 호출한다 (#178)")
    void processDueBillings_callsProcessBillingForEachDueSubscription() {
        // given
        Subscription sub1 = mock(Subscription.class);
        Subscription sub2 = mock(Subscription.class);
        given(sub1.getId()).willReturn(1L);
        given(sub2.getId()).willReturn(2L);
        given(subscriptionRepository.findDueForBilling(anyList(), any()))
                .willReturn(List.of(sub1, sub2));

        // when
        billingScheduler.processDueBillings();

        // then
        then(subscriptionService).should().processBilling(1L);
        then(subscriptionService).should().processBilling(2L);
    }

    @Test
    @DisplayName("한 건의 결제 처리가 실패해도 나머지 구독 결제는 계속 진행된다")
    void processDueBillings_oneFails_othersStillProcessed() {
        // given
        Subscription sub1 = mock(Subscription.class);
        Subscription sub2 = mock(Subscription.class);
        given(sub1.getId()).willReturn(1L);
        given(sub2.getId()).willReturn(2L);
        given(subscriptionRepository.findDueForBilling(anyList(), any()))
                .willReturn(List.of(sub1, sub2));
        willThrow(new RuntimeException("결제 실패")).given(subscriptionService).processBilling(1L);

        // when
        billingScheduler.processDueBillings();

        // then
        then(subscriptionService).should().processBilling(2L);
    }

    @Test
    @DisplayName("일부 결제가 실패해도 배치는 끝까지 실행되어 하트비트 게이지가 갱신된다 (#188)")
    void processDueBillings_completes_updatesHeartbeatGauge() {
        // given
        Subscription sub1 = mock(Subscription.class);
        given(sub1.getId()).willReturn(1L);
        given(subscriptionRepository.findDueForBilling(anyList(), any())).willReturn(List.of(sub1));
        willThrow(new RuntimeException("결제 실패")).given(subscriptionService).processBilling(1L);

        long before = java.time.Instant.now().getEpochSecond();

        // when
        billingScheduler.processDueBillings();

        // then
        double gaugeValue = meterRegistry.get("billing_last_run_timestamp_seconds").gauge().value();
        assertThat(gaugeValue).isGreaterThanOrEqualTo(before);
        then(batchHeartbeatService).should().recordSuccess(
                org.mockito.ArgumentMatchers.eq(BatchHeartbeatService.BILLING_BATCH),
                org.mockito.ArgumentMatchers.longThat(value -> value >= before));
    }

    @Test
    @DisplayName("시작 시 저장된 정기결제 성공 시각을 하트비트 게이지에 복원한다 (#303)")
    void registerMetrics_restoresPersistedHeartbeat() {
        // given
        long persisted = 1_786_719_600L;
        given(batchHeartbeatService.lastSuccessEpochSeconds(
                BatchHeartbeatService.BILLING_BATCH)).willReturn(persisted);
        SimpleMeterRegistry restoredRegistry = new SimpleMeterRegistry();
        BillingScheduler restored = new BillingScheduler(
                subscriptionRepository, subscriptionService, restoredRegistry, batchHeartbeatService);

        // when
        restored.registerMetrics();

        // then
        assertThat(restoredRegistry.get("billing_last_run_timestamp_seconds").gauge().value())
                .isEqualTo(persisted);
    }
}
