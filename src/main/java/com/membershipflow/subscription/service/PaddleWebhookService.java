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

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String TRANSACTION_COMPLETED = "transaction.completed";
    private static final String TRANSACTION_PAYMENT_FAILED = "transaction.payment_failed";
    private static final String SUBSCRIPTION_CREATED = "subscription.created";
    private static final String SUBSCRIPTION_UPDATED = "subscription.updated";
    private static final String SUBSCRIPTION_CANCELED = "subscription.canceled";

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
                .atZoneSameInstant(SERVICE_ZONE)
                .toLocalDateTime();

        if (webhookEventRepository.existsByPaymentProviderAndExternalEventId(
                PaymentProvider.PADDLE, eventId)) {
            return;
        }

        JsonNode data = event.path("data");
        switch (eventType) {
            case TRANSACTION_COMPLETED -> completeTransaction(data, occurredAt);
            case TRANSACTION_PAYMENT_FAILED -> failTransaction(data, occurredAt);
            case SUBSCRIPTION_CREATED, SUBSCRIPTION_UPDATED, SUBSCRIPTION_CANCELED ->
                    syncSubscription(data, occurredAt);
            default -> log.info("Paddle 처리 대상 외 이벤트 수신: eventType={}", eventType);
        }
        webhookEventRepository.save(new PaymentWebhookEvent(
                PaymentProvider.PADDLE, eventId, eventType, occurredAt));
    }

    private void completeTransaction(JsonNode data, LocalDateTime occurredAt) {
        String transactionId = requiredText(data, "id");
        PaymentHistory existingHistory = paymentHistoryRepository
                .findByExternalTransactionId(transactionId)
                .orElse(null);
        if (existingHistory != null && existingHistory.getStatus() == PaymentStatus.SUCCESS) {
            return;
        }
        String subscriptionId = requiredText(data, "subscription_id");
        Subscription recurringSubscription = subscriptionRepository
                .findByExternalSubscriptionIdForUpdate(subscriptionId)
                .orElse(null);
        if (recurringSubscription != null) {
            completeRenewal(data, recurringSubscription, transactionId,
                    existingHistory, occurredAt);
            return;
        }

        JsonNode customData = data.path("custom_data");
        String attemptId = requiredText(customData, "checkout_attempt_id");
        PaddleCheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH));

        validateTransaction(data, customData, attempt, transactionId, occurredAt);

        String customerId = requiredText(data, "customer_id");
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

    private void completeRenewal(JsonNode data, Subscription subscription,
                                 String transactionId, PaymentHistory existingHistory,
                                 LocalDateTime occurredAt) {
        if (!"completed".equals(data.path("status").asText())) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
        validateRecurringTransaction(data, subscription);
        LocalDateTime nextBillingAt = parseDateTime(
                requiredText(data.path("billing_period"), "ends_at"));
        subscription.syncPaddleActive(nextBillingAt, occurredAt);
        if (existingHistory == null) {
            paymentHistoryRepository.save(PaymentHistory.builder()
                    .member(subscription.getMember())
                    .subscription(subscription)
                    .paymentProvider(PaymentProvider.PADDLE)
                    .externalTransactionId(transactionId)
                    .amount(subscription.getPlan().getPrice())
                    .status(PaymentStatus.SUCCESS)
                    .billedAt(occurredAt)
                    .build());
        } else {
            existingHistory.completePaddle(occurredAt);
        }
    }

    private void failTransaction(JsonNode data, LocalDateTime occurredAt) {
        String transactionId = requiredText(data, "id");
        if (paymentHistoryRepository.findByExternalTransactionId(transactionId).isPresent()) {
            return;
        }
        String subscriptionId = data.path("subscription_id").asText(null);
        if (subscriptionId == null || subscriptionId.isBlank()) {
            failInitialTransaction(data, transactionId, occurredAt);
            return;
        }
        Subscription subscription = subscriptionRepository
                .findByExternalSubscriptionIdForUpdate(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH));
        validateRecurringTransaction(data, subscription);
        String errorCode = latestPaymentError(data.path("payments"));
        subscription.syncPaddlePaymentFailed(occurredAt);
        paymentHistoryRepository.save(PaymentHistory.builder()
                .member(subscription.getMember())
                .subscription(subscription)
                .paymentProvider(PaymentProvider.PADDLE)
                .externalTransactionId(transactionId)
                .amount(subscription.getPlan().getPrice())
                .status(PaymentStatus.FAIL)
                .failReason(errorCode)
                .billedAt(occurredAt)
                .build());
    }

    private void failInitialTransaction(JsonNode data, String transactionId,
                                        LocalDateTime occurredAt) {
        JsonNode customData = data.path("custom_data");
        String attemptId = requiredText(customData, "checkout_attempt_id");
        PaddleCheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH));
        boolean valid = attempt.isProcessable(occurredAt)
                && transactionId.equals(attempt.getExternalTransactionId())
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
        attempt.fail(occurredAt);
    }

    private void syncSubscription(JsonNode data, LocalDateTime occurredAt) {
        String subscriptionId = requiredText(data, "id");
        Subscription subscription = subscriptionRepository
                .findByExternalSubscriptionIdForUpdate(subscriptionId)
                .orElse(null);
        if (subscription == null) {
            log.warn("Paddle 최초 결제 확정 전 구독 이벤트 재시도 요청: subscriptionId={}",
                    subscriptionId);
            throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_RETRY_REQUIRED);
        }
        validateSubscription(data, subscription);

        JsonNode scheduledChange = data.path("scheduled_change");
        if ("cancel".equals(scheduledChange.path("action").asText())) {
            subscription.schedulePaddleCancellation(
                    parseDateTime(requiredText(scheduledChange, "effective_at")), occurredAt);
            return;
        }

        String status = requiredText(data, "status");
        switch (status) {
            case "active", "trialing" -> subscription.syncPaddleActive(
                    optionalDateTime(data, "next_billed_at"), occurredAt);
            case "past_due" -> subscription.syncPaddlePastDue(occurredAt);
            case "paused" -> subscription.syncPaddleSuspended(occurredAt);
            case "canceled" -> subscription.syncPaddleCanceled(
                    optionalDateTime(data, "canceled_at") == null
                            ? occurredAt : optionalDateTime(data, "canceled_at"),
                    occurredAt);
            default -> throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
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

    private void validateRecurringTransaction(JsonNode data, Subscription subscription) {
        boolean valid = subscription.getPaymentProvider() == PaymentProvider.PADDLE
                && "automatic".equals(data.path("collection_mode").asText())
                && "KRW".equals(data.path("currency_code").asText())
                && subscription.getPlan().getPrice()
                    == data.path("details").path("totals").path("grand_total").asInt(-1)
                && containsExpectedPrice(data.path("items"), subscription);
        if (!valid) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
    }

    private void validateSubscription(JsonNode data, Subscription subscription) {
        boolean valid = subscription.getPaymentProvider() == PaymentProvider.PADDLE
                && "automatic".equals(data.path("collection_mode").asText())
                && "KRW".equals(data.path("currency_code").asText())
                && containsExpectedPrice(data.path("items"), subscription);
        if (!valid) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
    }

    private boolean containsExpectedPrice(JsonNode items, Subscription subscription) {
        if (!items.isArray() || items.size() != 1) return false;
        JsonNode item = items.get(0);
        return item.path("quantity").asInt() == 1
                && priceResolver.resolve(subscription.getPlan().getBillingCycle())
                .equals(item.path("price").path("id").asText());
    }

    private String latestPaymentError(JsonNode payments) {
        if (!payments.isArray() || payments.isEmpty()) return "PADDLE_PAYMENT_FAILED";
        String errorCode = payments.get(payments.size() - 1).path("error_code").asText();
        return errorCode.isBlank() ? "PADDLE_PAYMENT_FAILED" : errorCode;
    }

    private LocalDateTime optionalDateTime(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : parseDateTime(value);
    }

    private LocalDateTime parseDateTime(String value) {
        return OffsetDateTime.parse(value)
                .atZoneSameInstant(SERVICE_ZONE)
                .toLocalDateTime();
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
