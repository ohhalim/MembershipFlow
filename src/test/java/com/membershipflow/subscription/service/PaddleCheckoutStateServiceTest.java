package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import com.membershipflow.subscription.entity.BillingCycle;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.repository.PaddleCheckoutAttemptRepository;
import com.membershipflow.subscription.repository.SubscriptionPlanRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaddleCheckoutStateServiceTest {

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private SubscriptionPlanRepository planRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PaddleCheckoutAttemptRepository attemptRepository;

    private PaddleCheckoutStateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new PaddleCheckoutStateService(
                memberRepository, planRepository, subscriptionRepository, attemptRepository);
    }

    @Test
    void failRejectedTransaction_marksUnattachedPendingAttemptFailed() {
        PaddleCheckoutAttempt attempt = new PaddleCheckoutAttempt(
                mock(Member.class), mock(SubscriptionPlan.class), LocalDateTime.now());
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        stateService.failRejectedTransaction(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo(PaddleCheckoutAttemptStatus.FAILED);
        assertThat(attempt.getCompletedAt()).isNotNull();
    }

    @Test
    void failRejectedTransaction_keepsAttemptWhenTransactionIsAlreadyAttached() {
        PaddleCheckoutAttempt attempt = new PaddleCheckoutAttempt(
                mock(Member.class), mock(SubscriptionPlan.class), LocalDateTime.now());
        attempt.attachTransaction("txn_test");
        when(attemptRepository.findByIdForUpdate(attempt.getId()))
                .thenReturn(Optional.of(attempt));

        stateService.failRejectedTransaction(attempt.getId());

        assertThat(attempt.getStatus()).isEqualTo(PaddleCheckoutAttemptStatus.PENDING);
        assertThat(attempt.getCompletedAt()).isNull();
    }

    @Test
    void create_returnsExistingTransactionForSamePlan() {
        Member member = mock(Member.class);
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        when(plan.getId()).thenReturn(20L);
        when(plan.getBillingCycle()).thenReturn(BillingCycle.MONTHLY);
        PaddleCheckoutAttempt attempt = new PaddleCheckoutAttempt(
                member, plan, LocalDateTime.now());
        attempt.attachTransaction("txn_existing");

        when(memberRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(member));
        when(planRepository.findByIdAndActiveTrue(20L)).thenReturn(Optional.of(plan));
        when(attemptRepository.findFirstByMemberIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(10L), eq(PaddleCheckoutAttemptStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.of(attempt));

        PaddleCheckoutStateService.CheckoutContext context = stateService.create(10L, 20L);

        assertThat(context.attemptId()).isEqualTo(attempt.getId());
        assertThat(context.existingTransactionId()).isEqualTo("txn_existing");
        verify(attemptRepository, never()).save(any(PaddleCheckoutAttempt.class));
    }

    @Test
    void create_blocksPendingAttemptWithoutAttachedTransaction() {
        Member member = mock(Member.class);
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        when(plan.getId()).thenReturn(20L);
        PaddleCheckoutAttempt attempt = new PaddleCheckoutAttempt(
                member, plan, LocalDateTime.now());

        when(memberRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(member));
        when(planRepository.findByIdAndActiveTrue(20L)).thenReturn(Optional.of(plan));
        when(attemptRepository.findFirstByMemberIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(10L), eq(PaddleCheckoutAttemptStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Optional.of(attempt));

        assertThatThrownBy(() -> stateService.create(10L, 20L))
                .isInstanceOf(BusinessException.class);
    }
}
