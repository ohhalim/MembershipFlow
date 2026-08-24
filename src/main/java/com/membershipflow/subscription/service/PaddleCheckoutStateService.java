package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.repository.PaddleCheckoutAttemptRepository;
import com.membershipflow.subscription.repository.SubscriptionPlanRepository;
import com.membershipflow.subscription.repository.SubscriptionRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaddleCheckoutStateService {

    private final MemberRepository memberRepository;
    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaddleCheckoutAttemptRepository attemptRepository;

    @Transactional
    public CheckoutContext create(Long memberId, Long planId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        SubscriptionPlan plan = planRepository.findByIdAndActiveTrue(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        subscriptionRepository.findByMemberId(memberId)
                .filter(subscription -> subscription.isActive())
                .ifPresent(subscription -> {
                    throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
                });

        LocalDateTime now = LocalDateTime.now();
        boolean pending = attemptRepository
                .existsByMemberIdAndStatusAndExpiresAtAfter(
                        memberId, PaddleCheckoutAttemptStatus.PENDING, now);
        if (pending) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }

        PaddleCheckoutAttempt attempt = attemptRepository.save(
                new PaddleCheckoutAttempt(member, plan, now));
        return new CheckoutContext(attempt.getId(), memberId, planId, plan.getBillingCycle());
    }

    @Transactional
    public void attachTransaction(String attemptId, String transactionId) {
        PaddleCheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (!attempt.isProcessable(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        attempt.attachTransaction(transactionId);
    }

    @Transactional
    public void failRejectedTransaction(String attemptId) {
        PaddleCheckoutAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (attempt.getStatus() == PaddleCheckoutAttemptStatus.PENDING
                && attempt.getExternalTransactionId() == null) {
            attempt.fail(LocalDateTime.now());
        }
    }

    public record CheckoutContext(
            String attemptId,
            Long memberId,
            Long planId,
            com.membershipflow.subscription.entity.BillingCycle billingCycle) {}
}
