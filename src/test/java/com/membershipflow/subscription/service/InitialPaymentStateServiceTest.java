package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.client.TossPaymentsClient;
import com.membershipflow.subscription.entity.BillingAttempt;
import com.membershipflow.subscription.entity.BillingAttemptStatus;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.BillingAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InitialPaymentStateServiceTest {

    @Mock BillingAttemptRepository billingAttemptRepository;
    @Mock MemberRepository memberRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PaymentHistoryRepository paymentHistoryRepository;
    @InjectMocks InitialPaymentStateService stateService;

    Member member;
    SubscriptionPlan plan;
    BillingAttempt attempt;

    @BeforeEach
    void setUp() {
        member = Member.builder().id(10L).email("sub@test.com").build();
        plan = org.mockito.Mockito.mock(SubscriptionPlan.class);
        lenient().when(plan.getName()).thenReturn("프리미엄");
        lenient().when(plan.getPrice()).thenReturn(9900);
        attempt = BillingAttempt.builder()
                .member(member)
                .plan(plan)
                .customerKey("customer-key")
                .build();
        attempt.startProcessing();
    }

    @Test
    void storeBillingKey_persistsRecoveryContextOnce() {
        given(billingAttemptRepository.findByCustomerKeyForUpdate("customer-key"))
                .willReturn(Optional.of(attempt));
        given(subscriptionRepository.findByMemberId(member.getId()))
                .willReturn(Optional.empty());

        var context = stateService.storeBillingKey(
                "customer-key", "encrypted-key", "ORDER-customer-key",
                "1234-****", "국민");

        assertThat(context.encryptedBillingKey()).isEqualTo("encrypted-key");
        assertThat(context.orderId()).isEqualTo("ORDER-customer-key");
        assertThat(attempt.getCardCompany()).isEqualTo("국민");
    }

    @Test
    void claim_marksAttemptProcessing_beforeExternalCall() {
        BillingAttempt fresh = BillingAttempt.builder()
                .member(member)
                .plan(plan)
                .customerKey("claim-key")
                .build();
        given(billingAttemptRepository.findByCustomerKey("claim-key"))
                .willReturn(Optional.of(fresh));
        given(memberRepository.findByIdForUpdate(member.getId()))
                .willReturn(Optional.of(member));
        given(billingAttemptRepository.findByCustomerKeyForUpdate("claim-key"))
                .willReturn(Optional.of(fresh));
        given(billingAttemptRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                member.getId(),
                java.util.Set.of(BillingAttemptStatus.PENDING, BillingAttemptStatus.PROCESSING)))
                .willReturn(List.of(fresh));
        given(subscriptionRepository.findByMemberId(member.getId()))
                .willReturn(Optional.empty());

        var context = stateService.claim("claim-key");

        assertThat(context.completed()).isFalse();
        assertThat(fresh.getStatus()).isEqualTo(BillingAttemptStatus.PROCESSING);
        assertThat(fresh.getProcessingAt()).isNotNull();
    }

    @Test
    void claim_returnsRecoveryContext_whileAttemptIsProcessing() {
        given(billingAttemptRepository.findByCustomerKey("customer-key"))
                .willReturn(Optional.of(attempt));
        given(memberRepository.findByIdForUpdate(member.getId()))
                .willReturn(Optional.of(member));
        given(billingAttemptRepository.findByCustomerKeyForUpdate("customer-key"))
                .willReturn(Optional.of(attempt));
        given(billingAttemptRepository.findAllByMemberIdAndStatusInOrderByIdAsc(
                member.getId(),
                java.util.Set.of(BillingAttemptStatus.PENDING, BillingAttemptStatus.PROCESSING)))
                .willReturn(List.of(attempt));

        var context = stateService.claim("customer-key");

        assertThat(context.processing()).isTrue();
        assertThat(context.completed()).isFalse();
    }

    @Test
    void complete_expiredCancelledSubscription_reactivatesExistingRow() {
        attempt.storeBillingKey(
                "encrypted-key", "ORDER-customer-key", "1234-****", "국민");
        Subscription cancelled = Subscription.builder()
                .member(member)
                .plan(plan)
                .billingKey("old-key")
                .customerKey("old-customer")
                .startedAt(LocalDateTime.now().minusMonths(2))
                .nextBillingAt(LocalDateTime.now().minusDays(1))
                .build();
        cancelled.cancel();
        given(billingAttemptRepository.findByCustomerKeyForUpdate("customer-key"))
                .willReturn(Optional.of(attempt));
        given(subscriptionRepository.findByMemberId(member.getId()))
                .willReturn(Optional.of(cancelled));
        given(paymentHistoryRepository.findByTossOrderId("ORDER-customer-key"))
                .willReturn(Optional.empty());
        var payment = new TossPaymentsClient.PaymentResponse(
                "payment-key", "2026-07-25", 9900, null);

        stateService.complete("customer-key", payment);

        assertThat(cancelled.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(cancelled.getBillingKey()).isEqualTo("encrypted-key");
        assertThat(cancelled.getCustomerKey()).isEqualTo("customer-key");
        assertThat(attempt.getStatus()).isEqualTo(BillingAttemptStatus.COMPLETED);
        then(subscriptionRepository).should(never()).save(any());
        ArgumentCaptor<PaymentHistory> historyCaptor =
                ArgumentCaptor.forClass(PaymentHistory.class);
        then(paymentHistoryRepository).should().save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getTossOrderId())
                .isEqualTo("ORDER-customer-key");
    }

    @Test
    void load_completedAttempt_returnsCompletedContextWithoutNewPayment() {
        attempt.complete();
        given(billingAttemptRepository.findByCustomerKeyForUpdate("customer-key"))
                .willReturn(Optional.of(attempt));

        var context = stateService.load("customer-key");

        assertThat(context.completed()).isTrue();
        then(subscriptionRepository).shouldHaveNoInteractions();
    }
}
