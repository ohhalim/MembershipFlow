package com.membershipflow.subscription.service;

import com.membershipflow.common.util.BillingKeyEncryptor;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.client.TossPaymentsClient;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.BillingAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.SubscriptionPlanRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final long SUBSCRIPTION_ID = 1L;

    @Mock MemberRepository           memberRepository;
    @Mock SubscriptionPlanRepository planRepository;
    @Mock BillingAttemptRepository   billingAttemptRepository;
    @Mock SubscriptionRepository     subscriptionRepository;
    @Mock PaymentHistoryRepository   paymentHistoryRepository;
    @Mock TossPaymentsClient         tossPaymentsClient;
    @Mock BillingKeyEncryptor        billingKeyEncryptor;

    @InjectMocks SubscriptionService subscriptionService;

    Member member;
    SubscriptionPlan plan;

    @BeforeEach
    void setUp() {
        member = Member.builder().id(10L).email("sub@test.com").build();
        plan = mock(SubscriptionPlan.class);
    }

    private Subscription subscriptionDueAt(LocalDateTime nextBillingAt) {
        Subscription sub = Subscription.builder()
                .member(member).plan(plan)
                .billingKey("enc-key").customerKey("customer-key")
                .startedAt(LocalDateTime.now().minusMonths(1))
                .nextBillingAt(nextBillingAt)
                .build();
        ReflectionTestUtils.setField(sub, "id", SUBSCRIPTION_ID);
        return sub;
    }

    @Test
    @DisplayName("결제일이 도래한 ACTIVE 구독은 정기결제를 실행한다")
    void processBilling_dueActiveSubscription_charges() {
        // given
        Subscription sub = subscriptionDueAt(LocalDateTime.now().minusHours(1));
        given(subscriptionRepository.findById(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));
        given(billingKeyEncryptor.decrypt("enc-key")).willReturn("raw-key");
        given(plan.getPrice()).willReturn(9900);
        given(plan.getName()).willReturn("프리미엄");
        given(tossPaymentsClient.charge(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .willReturn(new TossPaymentsClient.PaymentResponse("pay-key", "2026-07-10", 9900, null));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should()
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should().save(any());
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getNextBillingAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("취소된 구독은 결제일이 지났어도 과금하지 않는다 (#178 재검증 가드)")
    void processBilling_cancelledSubscription_skipsCharge() {
        // given — 배치 조회 이후 사용자가 취소한 상황
        Subscription sub = subscriptionDueAt(LocalDateTime.now().minusHours(1));
        sub.cancel();
        given(subscriptionRepository.findById(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should(never())
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("nextBillingAt이 미래면(이미 결제됨) 중복 과금하지 않는다 (#178 멱등성)")
    void processBilling_alreadyBilled_skipsCharge() {
        // given — 같은 배치에서 중복 호출됐거나 이미 갱신된 상황
        Subscription sub = subscriptionDueAt(LocalDateTime.now().plusDays(20));
        given(subscriptionRepository.findById(SUBSCRIPTION_ID)).willReturn(Optional.of(sub));

        // when
        subscriptionService.processBilling(SUBSCRIPTION_ID);

        // then
        then(tossPaymentsClient).should(never())
                .charge(anyString(), anyString(), anyInt(), anyString(), anyString());
        then(paymentHistoryRepository).should(never()).save(any());
    }
}
