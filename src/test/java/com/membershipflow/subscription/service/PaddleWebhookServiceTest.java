package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.member.entity.Member;
import com.membershipflow.subscription.entity.BillingCycle;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionPlan;
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

    @BeforeEach
    void setUp() {
        service = new PaddleWebhookService(
                new ObjectMapper(), verifier, priceResolver, attemptRepository,
                subscriptionRepository, paymentHistoryRepository, webhookEventRepository);

        Member member = Member.builder().id(10L).email("buyer@test.com").build();
        SubscriptionPlan plan = mock(SubscriptionPlan.class);
        when(plan.getId()).thenReturn(20L);
        when(plan.getPrice()).thenReturn(10_000);
        when(plan.getBillingCycle()).thenReturn(BillingCycle.MONTHLY);
        attempt = new PaddleCheckoutAttempt(member, plan, LocalDateTime.of(2026, 8, 24, 9, 50));
        org.springframework.test.util.ReflectionTestUtils.setField(attempt, "id", "attempt-id");
        attempt.attachTransaction("txn_test");
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
}
