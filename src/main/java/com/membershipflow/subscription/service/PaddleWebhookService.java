package com.membershipflow.subscription.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.PaymentStatus;
import com.membershipflow.subscription.entity.PaymentWebhookEvent;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.PaddleCheckoutAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.PaymentWebhookEventRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaddleWebhookService {

    private static final String TRANSACTION_COMPLETED = "transaction.completed";

    private final ObjectMapper objectMapper;
    private final PaddleWebhookVerifier verifier;
    private final PaddlePriceResolver priceResolver;
    private final PaddleCheckoutAttemptRepository attemptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;

    @Transactional
    public void handle(String rawBody, String signatureHeader) {
        verifier.verify(rawBody, signatureHeader);
        JsonNode event = parse(rawBody);
        process(event);
    }

    private void process(JsonNode event) {
        String eventId = requiredText(event, "event_id");
        String eventType = requiredText(event, "event_type");
        LocalDateTime occurredAt = OffsetDateTime.parse(requiredText(event, "occurred_at"))
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime();

        if (webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, eventId)) {
            return;
        }

        if (TRANSACTION_COMPLETED.equals(eventType)) {
            completeTransaction(event.path("data"), occurredAt);
        }
        webhookEventRepository.save(new PaymentWebhookEvent(
                PaymentProvider.PADDLE, eventId, eventType, occurredAt));
    }

    private void completeTransaction(JsonNode data, LocalDateTime occurredAt) {
        JsonNode customData = data.path("custom_data");
        String attemptId = requiredText(customData, "checkout_attempt_id");
        PaddleCheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH));

        String transactionId = requiredText(data, "id");
        if (paymentHistoryRepository.findByExternalTransactionId(transactionId).isPresent()) {
            return;
        }
        validateTransaction(data, customData, attempt, transactionId, occurredAt);

        String customerId = requiredText(data, "customer_id");
        String subscriptionId = requiredText(data, "subscription_id");
        Subscription subscription = subscriptionRepository
                .findByMemberId(attempt.getMember().getId())
                .orElse(null);
        LocalDateTime nextBillingAt = attempt.getPlan().getBillingCycle().nextBillingAt(occurredAt);
        if (subscription == null) {
            subscription = subscriptionRepository.save(Subscription.paddle(
                    attempt.getMember(), attempt.getPlan(), customerId, subscriptionId,
                    occurredAt, nextBillingAt));
        } else if (!subscription.isActive()) {
            subscription.resubscribeWithPaddle(
                    attempt.getPlan(), customerId, subscriptionId, occurredAt, nextBillingAt);
        } else {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        }

        paymentHistoryRepository.save(PaymentHistory.builder()
                .member(attempt.getMember())
                .subscription(subscription)
                .paymentProvider(PaymentProvider.PADDLE)
                .externalTransactionId(transactionId)
                .amount(attempt.getPlan().getPrice())
                .status(PaymentStatus.SUCCESS)
                .billedAt(occurredAt)
                .build());
        attempt.complete(occurredAt);
    }

    private void validateTransaction(JsonNode data, JsonNode customData,
                                     PaddleCheckoutAttempt attempt, String transactionId,
                                     LocalDateTime occurredAt) {
        boolean valid = attempt.isProcessable(occurredAt)
                && transactionId.equals(attempt.getExternalTransactionId())
                && "completed".equals(data.path("status").asText())
                && "automatic".equals(data.path("collection_mode").asText())
                && "KRW".equals(data.path("currency_code").asText())
                && attempt.getMember().getId().equals(customData.path("member_id").asLong())
                && attempt.getPlan().getId().equals(customData.path("plan_id").asLong())
                && attempt.getPlan().getPrice()
                    == data.path("details").path("totals").path("grand_total").asInt(-1)
                && containsExpectedPrice(data.path("items"), attempt);
        if (!valid) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
    }

    private boolean containsExpectedPrice(JsonNode items, PaddleCheckoutAttempt attempt) {
        if (!items.isArray() || items.size() != 1) return false;
        JsonNode item = items.get(0);
        return item.path("quantity").asInt() == 1
                && priceResolver.resolve(attempt.getPlan().getBillingCycle())
                .equals(item.path("price").path("id").asText());
    }

    private JsonNode parse(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
        return value;
    }
}
