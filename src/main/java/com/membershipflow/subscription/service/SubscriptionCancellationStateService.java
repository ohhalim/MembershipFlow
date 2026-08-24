package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.subscription.dto.CancelResponse;
import com.membershipflow.subscription.entity.PaymentProvider;
import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionCancellationStateService {

    private final SubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public CancellationContext prepare(Long memberId) {
        Subscription subscription = subscriptionRepository.findByMemberId(memberId)
                .filter(Subscription::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        return new CancellationContext(
                subscription.getPaymentProvider(),
                subscription.getExternalSubscriptionId());
    }

    @Transactional
    public CancelResponse completeToss(Long memberId) {
        Subscription subscription = findForUpdate(memberId);
        subscription.cancel();
        return CancelResponse.from(subscription);
    }

    @Transactional
    public CancelResponse completePaddle(Long memberId, LocalDateTime serviceEndsAt) {
        Subscription subscription = findForUpdate(memberId);
        if (subscription.getPaymentProvider() != PaymentProvider.PADDLE) {
            throw new BusinessException(ErrorCode.PAYMENT_DATA_MISMATCH);
        }
        subscription.schedulePaddleCancellation(
                serviceEndsAt, LocalDateTime.now());
        return CancelResponse.from(subscription);
    }

    private Subscription findForUpdate(Long memberId) {
        return subscriptionRepository.findByMemberIdForUpdate(memberId)
                .filter(Subscription::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }

    public record CancellationContext(
            PaymentProvider paymentProvider,
            String externalSubscriptionId
    ) {}
}
