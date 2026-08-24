package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.membershipflow.member.entity.Member;
import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubscriptionCancellationStateServiceTest {

    @Mock SubscriptionRepository subscriptionRepository;
    @InjectMocks SubscriptionCancellationStateService service;

    @Test
    void completePaddle_preservesAccessUntilPaddleEffectiveDate() {
        Member member = Member.builder().id(10L).email("buyer@test.com").build();
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 24, 10, 0);
        LocalDateTime serviceEndsAt = LocalDateTime.of(2026, 9, 24, 10, 0);
        Subscription subscription = Subscription.paddle(
                member, plan, "ctm_test", "sub_test", startedAt, serviceEndsAt);
        given(subscriptionRepository.findByMemberIdForUpdate(member.getId()))
                .willReturn(Optional.of(subscription));

        var response = service.completePaddle(member.getId(), serviceEndsAt);

        assertThat(response.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(response.serviceEndsAt()).isEqualTo(serviceEndsAt);
        assertThat(subscription.getPaymentProvider()).isEqualTo(PaymentProvider.PADDLE);
        assertThat(subscription.isActiveAt(serviceEndsAt.minusSeconds(1))).isTrue();
        assertThat(subscription.isActiveAt(serviceEndsAt)).isFalse();
    }
}
