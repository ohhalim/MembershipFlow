package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.member.repository.MemberRepository;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InitialPaymentStateService {

    private static final Duration ABANDONED_BILLING_ISSUE_TIMEOUT = Duration.ofMinutes(10);

    private final BillingAttemptRepository billingAttemptRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;

    @Transactional
    public InitialPaymentContext load(String customerKey) {
        BillingAttempt attempt = findLocked(customerKey);
        if (attempt.getStatus() == BillingAttemptStatus.COMPLETED) {
            return contextOf(attempt, true, false);
        }
        if (attempt.getStatus() == BillingAttemptStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        validatePending(attempt);
        rejectActiveSubscription(attempt);
        return contextOf(attempt, false, false);
    }

    /**
     * 콜백의 외부 호출을 시작하기 전에 회원 단위로 결제 시도를 선점한다.
     * 같은 회원의 두 콜백이 서로 다른 customerKey를 사용해도 한 건만 PROCESSING이 된다.
     */
    @Transactional
    public InitialPaymentContext claim(String customerKey) {
        BillingAttempt reference = billingAttemptRepository.findByCustomerKey(customerKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        memberRepository.findByIdForUpdate(reference.getMember().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        BillingAttempt attempt = findLocked(customerKey);
        if (attempt.getStatus() == BillingAttemptStatus.COMPLETED) {
            return contextOf(attempt, true, false);
        }

        List<BillingAttempt> attempts = billingAttemptRepository
                .findAllByMemberIdAndStatusInOrderByIdAsc(
                        attempt.getMember().getId(),
                        Set.of(BillingAttemptStatus.PENDING, BillingAttemptStatus.PROCESSING));
        expireUnstartedAttempts(attempts);

        long activeCount = attempts.stream()
                .filter(this::isActiveAttempt)
                .count();
        if (activeCount > 1) {
            // 과거 중복 데이터는 자동 선택·정리하지 않고 운영 확인을 요구한다.
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }

        if (isAbandonedBillingIssue(attempt)) {
            attempt.fail();
            return contextOf(attempt, false, false, true);
        }
        if (attempt.getStatus() == BillingAttemptStatus.PROCESSING) {
            if (attempt.getIssueIdempotencyKey() == null) {
                // V19 이전 PROCESSING은 기존 외부 요청 결과를 알 수 없어 새 키로 재호출하지 않는다.
                throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            }
            return contextOf(attempt, false, true);
        }
        if (attempt.getBillingKey() != null || attempt.getOrderId() != null) {
            // V14 이전 흐름에서 저장된 복구 정보는 승인 상태 대조 전까지 재사용하지 않는다.
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        validatePending(attempt);
        rejectActiveSubscription(attempt);
        attempt.ensureIssueIdempotencyKey();
        attempt.startProcessing();
        return contextOf(attempt, false, false);
    }

    @Transactional
    public InitialPaymentContext storeBillingKey(String customerKey, String encryptedBillingKey,
                                                  String orderId, String cardNumberMasked,
                                                  String cardCompany) {
        BillingAttempt attempt = findLocked(customerKey);
        validateProcessing(attempt);
        rejectActiveSubscription(attempt);
        attempt.storeBillingKey(
                encryptedBillingKey, orderId, cardNumberMasked, cardCompany);
        return contextOf(attempt, false, false);
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
        validateProcessing(attempt);
        if (attempt.getBillingKey() == null || attempt.getOrderId() == null) {
            throw new BusinessException(ErrorCode.BILLING_KEY_ISSUE_FAILED);
        }
        if (!isExpectedCompletedPayment(attempt, paymentResponse)) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
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

    private void validateProcessing(BillingAttempt attempt) {
        if (attempt.getStatus() != BillingAttemptStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
    }

    private void expireUnstartedAttempts(List<BillingAttempt> attempts) {
        LocalDateTime now = LocalDateTime.now();
        attempts.stream()
                .filter(attempt -> attempt.getStatus() == BillingAttemptStatus.PENDING)
                .filter(attempt -> !attempt.getExpiresAt().isAfter(now))
                .filter(attempt -> attempt.getBillingKey() == null && attempt.getOrderId() == null)
                .forEach(BillingAttempt::expire);
    }

    private boolean isActiveAttempt(BillingAttempt attempt) {
        if (attempt.getStatus() == BillingAttemptStatus.PROCESSING) return true;
        if (attempt.getStatus() != BillingAttemptStatus.PENDING) return false;
        // 기존 복구 필드가 있는 PENDING은 승인 여부를 확인하기 전까지 재사용하지 않는다.
        if (attempt.getBillingKey() != null || attempt.getOrderId() != null) return true;
        return attempt.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean isAbandonedBillingIssue(BillingAttempt attempt) {
        return attempt.getStatus() == BillingAttemptStatus.PROCESSING
                && attempt.getBillingKey() == null
                && attempt.getOrderId() == null
                && attempt.getProcessingAt() != null
                && attempt.getProcessingAt().isBefore(
                        LocalDateTime.now().minus(ABANDONED_BILLING_ISSUE_TIMEOUT));
    }

    private void rejectActiveSubscription(BillingAttempt attempt) {
        subscriptionRepository.findByMemberId(attempt.getMember().getId())
                .filter(Subscription::isActive)
                .ifPresent(sub -> {
                    throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
                });
    }

    private InitialPaymentContext contextOf(BillingAttempt attempt, boolean completed, boolean processing) {
        return contextOf(attempt, completed, processing, false);
    }

    private InitialPaymentContext contextOf(BillingAttempt attempt, boolean completed,
                                            boolean processing, boolean reauthenticationRequired) {
        return new InitialPaymentContext(
                attempt.getCustomerKey(), attempt.getMember().getId(),
                attempt.getPlan().getName(), attempt.getPlan().getPrice(),
                attempt.getBillingKey(), attempt.getOrderId(),
                attempt.getIssueIdempotencyKey(), attempt.getChargeIdempotencyKey(),
                completed, processing, reauthenticationRequired);
    }

    private boolean isExpectedCompletedPayment(
            BillingAttempt attempt, TossPaymentsClient.PaymentResponse paymentResponse) {
        return paymentResponse != null
                && paymentResponse.paymentKey() != null
                && attempt.getOrderId().equals(paymentResponse.orderId())
                && attempt.getPlan().getPrice() == paymentResponse.totalAmount()
                && "DONE".equals(paymentResponse.status())
                && "BILLING".equals(paymentResponse.type());
    }

    public record InitialPaymentContext(
            String customerKey,
            Long memberId,
            String planName,
            int amount,
            String encryptedBillingKey,
            String orderId,
            String issueIdempotencyKey,
            String chargeIdempotencyKey,
            boolean completed,
            boolean processing,
            boolean reauthenticationRequired) {
        public InitialPaymentContext(String customerKey, Long memberId, String planName,
                                     int amount, String encryptedBillingKey, String orderId,
                                     boolean completed) {
            this(customerKey, memberId, planName, amount, encryptedBillingKey, orderId,
                    null, null, completed, false, false);
        }

        public InitialPaymentContext(String customerKey, Long memberId, String planName,
                                     int amount, String encryptedBillingKey, String orderId,
                                     boolean completed, boolean processing) {
            this(customerKey, memberId, planName, amount, encryptedBillingKey, orderId,
                    null, null, completed, processing, false);
        }

        public InitialPaymentContext(String customerKey, Long memberId, String planName,
                                     int amount, String encryptedBillingKey, String orderId,
                                     String issueIdempotencyKey, String chargeIdempotencyKey,
                                     boolean completed, boolean processing) {
            this(customerKey, memberId, planName, amount, encryptedBillingKey, orderId,
                    issueIdempotencyKey, chargeIdempotencyKey,
                    completed, processing, false);
        }
    }
}
