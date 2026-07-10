package com.membershipflow.subscription.scheduler;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

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

    @InjectMocks BillingScheduler billingScheduler;

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
}
