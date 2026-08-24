package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.member.entity.Member;
import com.membershipflow.subscription.entity.BillingCycle;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import com.membershipflow.subscription.repository.PaddleCheckoutAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.PaymentWebhookEventRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaddleWebhookServiceTest {

    @Mock PaddleWebhookVerifier verifier;
    @Mock PaddlePriceResolver priceResolver;
    @Mock PaddleCheckoutAttemptRepository attemptRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock PaymentHistoryRepository paymentHistoryRepository;
    @Mock PaymentWebhookEventRepository webhookEventRepository;

    private PaddleWebhookService service;
    private PaddleCheckoutAttempt attempt;
    private Subscription paddleSubscription;

    @BeforeEach
    void setUp() {
        service = new PaddleWebhookService(
                new ObjectMapper(), verifier, priceResolver, attemptRepository,
                subscriptionRepository, paymentHistoryRepository, webhookEventRepository);

        Member member = Member.builder().id(10L).email("buyer@test.com").build();
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        lenient().when(plan.getId()).thenReturn(20L);
        lenient().when(plan.getPrice()).thenReturn(10_000);
        lenient().when(plan.getBillingCycle()).thenReturn(BillingCycle.MONTHLY);
        attempt = new PaddleCheckoutAttempt(member, plan, LocalDateTime.of(2026, 8, 24, 9, 50));
        org.springframework.test.util.ReflectionTestUtils.setField(attempt, "id", "attempt-id");
        attempt.attachTransaction("txn_test");
        paddleSubscription = Subscription.paddle(
                member, plan, "ctm_test", "sub_test",
                LocalDateTime.of(2026, 8, 24, 10, 0),
                LocalDateTime.of(2026, 9, 24, 10, 0));
    }

    @Test
    void completedTransaction_activatesSubscriptionAfterCrossValidation() {
        given(webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, "evt_test")).willReturn(false);
        given(attemptRepository.findByIdForUpdate("attempt-id")).willReturn(Optional.of(attempt));
        given(paymentHistoryRepository.findByExternalTransactionId("txn_test"))
                .willReturn(Optional.empty());
        given(priceResolver.resolve(BillingCycle.MONTHLY)).willReturn("pri_monthly");
        given(subscriptionRepository.findByMemberId(10L)).willReturn(Optional.empty());
        given(subscriptionRepository.save(any(Subscription.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.handle(completedEvent(), "signed-header");

        assertThat(attempt.getStatus()).isEqualTo(PaddleCheckoutAttemptStatus.COMPLETED);
        ArgumentCaptor<PaymentHistory> history = ArgumentCaptor.forClass(PaymentHistory.class);
        then(paymentHistoryRepository).should().save(history.capture());
        assertThat(history.getValue().getPaymentProvider()).isEqualTo(PaymentProvider.PADDLE);
        assertThat(history.getValue().getExternalTransactionId()).isEqualTo("txn_test");
        then(verifier).should().verify(completedEvent(), "signed-header");
    }

    @Test
    void completedRenewal_updatesNextBillingDateAndPaymentHistory() {
        given(webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, "evt_renewal")).willReturn(false);
        given(paymentHistoryRepository.findByExternalTransactionId("txn_renewal"))
                .willReturn(Optional.empty());
        given(subscriptionRepository.findByExternalSubscriptionIdForUpdate("sub_test"))
                .willReturn(Optional.of(paddleSubscription));
        given(priceResolver.resolve(BillingCycle.MONTHLY)).willReturn("pri_monthly");

        service.handle(renewalCompletedEvent(), "signed-header");

        assertThat(paddleSubscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(paddleSubscription.getNextBillingAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 10, 0));
        then(paymentHistoryRepository).should().save(any(PaymentHistory.class));
    }

    @Test
    void failedRenewal_marksPaymentFailureAndRecordsFailure() {
        given(webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, "evt_failed")).willReturn(false);
        given(paymentHistoryRepository.findByExternalTransactionId("txn_failed"))
                .willReturn(Optional.empty());
        given(subscriptionRepository.findByExternalSubscriptionIdForUpdate("sub_test"))
                .willReturn(Optional.of(paddleSubscription));
        given(priceResolver.resolve(BillingCycle.MONTHLY)).willReturn("pri_monthly");

        service.handle(paymentFailedEvent(), "signed-header");

        assertThat(paddleSubscription.getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_FAILED);
        ArgumentCaptor<PaymentHistory> history = ArgumentCaptor.forClass(PaymentHistory.class);
        then(paymentHistoryRepository).should().save(history.capture());
        assertThat(history.getValue().getFailReason()).isEqualTo("card_declined");
    }

    @Test
    void updatedSubscription_synchronizesScheduledCancellation() {
        given(webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, "evt_subscription_updated")).willReturn(false);
        given(subscriptionRepository.findByExternalSubscriptionIdForUpdate("sub_test"))
                .willReturn(Optional.of(paddleSubscription));
        given(priceResolver.resolve(BillingCycle.MONTHLY)).willReturn("pri_monthly");

        service.handle(subscriptionUpdatedEvent(), "signed-header");

        assertThat(paddleSubscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(paddleSubscription.getNextBillingAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 24, 10, 0));
    }

    private String completedEvent() {
        return """
                {
                  "event_id":"evt_test",
                  "event_type":"transaction.completed",
                  "occurred_at":"2026-08-24T01:00:00Z",
                  "data":{
                    "id":"txn_test",
                    "status":"completed",
                    "customer_id":"ctm_test",
                    "subscription_id":"sub_test",
                    "collection_mode":"automatic",
                    "currency_code":"KRW",
                    "custom_data":{
                      "checkout_attempt_id":"attempt-id",
                      "member_id":10,
                      "plan_id":20
                    },
                    "items":[{"quantity":1,"price":{"id":"pri_monthly"}}],
                    "details":{"totals":{"grand_total":"10000"}}
                  }
                }
                """;
    }

    private String renewalCompletedEvent() {
        return """
                {
                  "event_id":"evt_renewal",
                  "event_type":"transaction.completed",
                  "occurred_at":"2026-09-24T01:00:00Z",
                  "data":{
                    "id":"txn_renewal",
                    "status":"completed",
                    "subscription_id":"sub_test",
                    "collection_mode":"automatic",
                    "currency_code":"KRW",
                    "billing_period":{"ends_at":"2026-10-24T01:00:00Z"},
                    "items":[{"quantity":1,"price":{"id":"pri_monthly"}}],
                    "details":{"totals":{"grand_total":"10000"}}
                  }
                }
                """;
    }

    private String paymentFailedEvent() {
        return """
                {
                  "event_id":"evt_failed",
                  "event_type":"transaction.payment_failed",
                  "occurred_at":"2026-09-24T01:00:00Z",
                  "data":{
                    "id":"txn_failed",
                    "subscription_id":"sub_test",
                    "collection_mode":"automatic",
                    "currency_code":"KRW",
                    "items":[{"quantity":1,"price":{"id":"pri_monthly"}}],
                    "details":{"totals":{"grand_total":"10000"}},
                    "payments":[{"error_code":"card_declined"}]
                  }
                }
                """;
    }

    private String subscriptionUpdatedEvent() {
        return """
                {
                  "event_id":"evt_subscription_updated",
                  "event_type":"subscription.updated",
                  "occurred_at":"2026-08-25T01:00:00Z",
                  "data":{
                    "id":"sub_test",
                    "status":"active",
                    "collection_mode":"automatic",
                    "currency_code":"KRW",
                    "items":[{"quantity":1,"price":{"id":"pri_monthly"}}],
                    "scheduled_change":{
                      "action":"cancel",
                      "effective_at":"2026-09-24T01:00:00Z"
                    }
                  }
                }
                """;
    }
}
