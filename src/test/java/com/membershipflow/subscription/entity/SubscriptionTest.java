package com.membershipflow.subscription.entity;

import com.membershipflow.member.entity.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SubscriptionTest {

    private Subscription subscriptionWithNextBillingAt(LocalDateTime nextBillingAt) {
        return Subscription.builder()
                .member(Member.builder().id(1L).email("a@a.com").build())
                .plan(mock(SubscriptionPlan.class))
                .billingKey("key").customerKey("ck")
                .startedAt(LocalDateTime.now().minusMonths(1))
                .nextBillingAt(nextBillingAt)
                .build();
    }

    @Test
    @DisplayName("ACTIVE 구독은 활성이다")
    void isActive_activeStatus_true() {
        Subscription sub = subscriptionWithNextBillingAt(LocalDateTime.now().plusDays(20));
        assertThat(sub.isActive()).isTrue();
    }

    @Test
    @DisplayName("취소해도 이미 결제한 기간(nextBillingAt 이전)에는 활성이다 (#180)")
    void isActive_cancelledBeforeNextBilling_true() {
        Subscription sub = subscriptionWithNextBillingAt(LocalDateTime.now().plusDays(20));
        sub.cancel();
        assertThat(sub.isActive()).isTrue();
    }

    @Test
    @DisplayName("취소 후 결제 기간이 지나면 비활성이다")
    void isActive_cancelledAfterNextBilling_false() {
        Subscription sub = subscriptionWithNextBillingAt(LocalDateTime.now().minusDays(1));
        sub.cancel();
        assertThat(sub.isActive()).isFalse();
    }

    @Test
    @DisplayName("결제 실패(PAYMENT_FAILED) 상태는 비활성이다")
    void isActive_paymentFailed_false() {
        Subscription sub = subscriptionWithNextBillingAt(LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(sub, "status", SubscriptionStatus.PAYMENT_FAILED);
        assertThat(sub.isActive()).isFalse();
    }
}
