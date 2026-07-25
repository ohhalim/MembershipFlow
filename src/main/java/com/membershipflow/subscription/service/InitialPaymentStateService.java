package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.subscription.client.TossPaymentsClient;
import com.membershipflow.subscription.dto.SubscriptionResponse;
import com.membershipflow.subscription.entity.BillingAttempt;
import com.membershipflow.subscription.entity.BillingAttemptStatus;
import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentStatus;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.BillingAttemptRepository;
import com.membershipflow.subscription.repository.PaymentHistoryRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InitialPaymentStateService {

    private final BillingAttemptRepository billingAttemptRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    @Transactional
    public InitialPaymentContext load(String customerKey) {
        BillingAttempt attempt = findLocked(customerKey);
        if (attempt.getStatus() == BillingAttemptStatus.COMPLETED) {
            return contextOf(attempt, true);
        }
        validatePending(attempt);
        rejectActiveSubscription(attempt);
        return contextOf(attempt, false);
    }

    @Transactional
    public InitialPaymentContext storeBillingKey(String customerKey, String encryptedBillingKey,
                                                  String orderId, String cardNumberMasked,
                                                  String cardCompany) {
        BillingAttempt attempt = findLocked(customerKey);
        validatePending(attempt);
        rejectActiveSubscription(attempt);
        attempt.storeBillingKey(
                encryptedBillingKey, orderId, cardNumberMasked, cardCompany);
        return contextOf(attempt, false);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getCompletedSubscription(Long memberId) {
        return subscriptionRepository.findByMemberId(memberId)
                .map(SubscriptionResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }

    @Transactional
    public SubscriptionResponse complete(String customerKey,
                                         TossPaymentsClient.PaymentResponse paymentResponse) {
        BillingAttempt attempt = findLocked(customerKey);
        if (attempt.getStatus() == BillingAttemptStatus.COMPLETED) {
            return getCompletedSubscription(attempt.getMember().getId());
        }
        validatePending(attempt);
        if (attempt.getBillingKey() == null || attempt.getOrderId() == null) {
            throw new BusinessException(ErrorCode.BILLING_KEY_ISSUE_FAILED);
        }

        Subscription subscription = subscriptionRepository
                .findByMemberId(attempt.getMember().getId())
                .orElse(null);
        if (subscription != null && subscription.isActive()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextBillingAt = now.plusMonths(1);
        if (subscription != null) {
            subscription.resubscribe(
                    attempt.getPlan(), attempt.getBillingKey(), attempt.getCustomerKey(),
                    attempt.getCardNumberMasked(), attempt.getCardCompany(),
                    now, nextBillingAt);
        } else {
            subscription = subscriptionRepository.save(Subscription.builder()
                    .member(attempt.getMember())
                    .plan(attempt.getPlan())
                    .billingKey(attempt.getBillingKey())
                    .customerKey(attempt.getCustomerKey())
                    .cardNumberMasked(attempt.getCardNumberMasked())
                    .cardCompany(attempt.getCardCompany())
                    .startedAt(now)
                    .nextBillingAt(nextBillingAt)
                    .build());
        }

        if (paymentHistoryRepository.findByTossOrderId(attempt.getOrderId()).isEmpty()) {
            paymentHistoryRepository.save(PaymentHistory.builder()
                    .member(attempt.getMember())
                    .subscription(subscription)
                    .tossOrderId(attempt.getOrderId())
                    .tossPaymentKey(paymentResponse.paymentKey())
                    .amount(attempt.getPlan().getPrice())
                    .status(PaymentStatus.SUCCESS)
                    .billedAt(now)
                    .build());
        }
        attempt.complete();
        return SubscriptionResponse.from(subscription);
    }

    private BillingAttempt findLocked(String customerKey) {
        return billingAttemptRepository.findByCustomerKeyForUpdate(customerKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }

    private void validatePending(BillingAttempt attempt) {
        if (attempt.getStatus() != BillingAttemptStatus.PENDING
                || !attempt.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        }
    }

    private void rejectActiveSubscription(BillingAttempt attempt) {
        subscriptionRepository.findByMemberId(attempt.getMember().getId())
                .filter(Subscription::isActive)
                .ifPresent(sub -> {
                    throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
                });
    }

    private InitialPaymentContext contextOf(BillingAttempt attempt, boolean completed) {
        return new InitialPaymentContext(
                attempt.getCustomerKey(), attempt.getMember().getId(),
                attempt.getPlan().getName(), attempt.getPlan().getPrice(),
                attempt.getBillingKey(), attempt.getOrderId(), completed);
    }

    public record InitialPaymentContext(
            String customerKey,
            Long memberId,
            String planName,
            int amount,
            String encryptedBillingKey,
            String orderId,
            boolean completed) {}
}
