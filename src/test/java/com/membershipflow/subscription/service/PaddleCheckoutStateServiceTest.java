package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
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
}
