package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.common.util.BillingKeyEncryptor;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.client.TossPaymentsClient;
import com.membershipflow.subscription.dto.*;
import com.membershipflow.subscription.entity.*;
import com.membershipflow.subscription.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final DateTimeFormatter BILLING_CYCLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Duration ABANDONED_BILLING_ISSUE_TIMEOUT = Duration.ofMinutes(10);

    private final MemberRepository              memberRepository;
    private final SubscriptionPlanRepository    planRepository;
    private final BillingAttemptRepository      billingAttemptRepository;
    private final SubscriptionRepository        subscriptionRepository;
    private final PaymentHistoryRepository      paymentHistoryRepository;
    private final TossPaymentsClient            tossPaymentsClient;
    private final BillingKeyEncryptor           billingKeyEncryptor;
    private final InitialPaymentStateService    initialPaymentStateService;

    @Value("${toss.client-key}")
    private String tossClientKey;

    /** 플랜 목록 조회 */
    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> getPlans() {
        return planRepository.findAllByActiveTrueOrderById().stream()
                .map(SubscriptionPlanResponse::from)
                .toList();
    }

    /**
     * 빌링 준비: BillingAttempt 생성 → Toss 카드 등록 화면에 넘길 customerKey + clientKey 반환
     */
    @Transactional
    public BillingPrepareResponse prepare(Long memberId, Long planId) {
        Member member = memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        SubscriptionPlan plan = planRepository.findByIdAndActiveTrue(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 이미 활성 구독이면 신규 등록 불가
        subscriptionRepository.findByMemberId(memberId).ifPresent(sub -> {
            if (sub.isActive()) throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        });

        List<BillingAttempt> attempts = billingAttemptRepository
                .findAllByMemberIdAndStatusInOrderByIdAsc(
                        memberId, Set.of(BillingAttemptStatus.PENDING, BillingAttemptStatus.PROCESSING));
        expireUnstartedAttempts(attempts);
        failAbandonedBillingIssueAttempts(attempts);

        List<BillingAttempt> activeAttempts = attempts.stream()
                .filter(this::isActiveAttempt)
                .toList();
        if (activeAttempts.stream().anyMatch(attempt ->
                attempt.getStatus() == BillingAttemptStatus.PROCESSING)) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        if (activeAttempts.size() > 1) {
            // 기존 운영 중복 데이터는 임의로 선택하지 않고 수동 상태 대조를 요구한다.
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        if (activeAttempts.size() == 1) {
            BillingAttempt active = activeAttempts.get(0);
            if (!Objects.equals(active.getPlan().getId(), planId)) {
                throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            }
            return new BillingPrepareResponse(
                    active.getCustomerKey(), tossClientKey, active.getPlan().getId());
        }

        String customerKey = UUID.randomUUID().toString();
        BillingAttempt attempt = BillingAttempt.builder()
                .member(member)
                .plan(plan)
                .customerKey(customerKey)
                .build();
        billingAttemptRepository.save(attempt);

        return new BillingPrepareResponse(customerKey, tossClientKey, planId);
    }

    /**
     * 카드 등록 콜백: authKey + customerKey → 빌링 키 발급 → 최초 결제 → Subscription 저장
     */
    public SubscriptionResponse handleCallback(String customerKey, String authKey) {
        InitialPaymentStateService.InitialPaymentContext context =
                initialPaymentStateService.claim(customerKey);
        if (context.completed()) {
            return initialPaymentStateService.getCompletedSubscription(context.memberId());
        }

        if (context.encryptedBillingKey() == null) {
            TossPaymentsClient.BillingKeyResponse billingKeyResponse =
                    tossPaymentsClient.issueBillingKey(
                            customerKey, authKey, context.issueIdempotencyKey());
            if (billingKeyResponse == null
                    || billingKeyResponse.billingKey() == null
                    || !customerKey.equals(billingKeyResponse.customerKey())) {
                throw new BusinessException(ErrorCode.BILLING_KEY_ISSUE_FAILED);
            }

            String cardNumber = billingKeyResponse.cardNumber() != null
                    ? billingKeyResponse.cardNumber()
                    : billingKeyResponse.card() != null ? billingKeyResponse.card().number() : null;
            String cardCompany = billingKeyResponse.cardCompany();
            context = initialPaymentStateService.storeBillingKey(
                    customerKey,
                    billingKeyEncryptor.encrypt(billingKeyResponse.billingKey()),
                    "ORDER-" + customerKey,
                    cardNumber,
                    cardCompany);
        }

        InitialPaymentStateService.InitialPaymentContext paymentContext = context;
        if (paymentContext.chargeIdempotencyKey() == null) {
            TossPaymentsClient.PaymentResponse recovered =
                    tossPaymentsClient.findPaymentByOrderId(paymentContext.orderId())
                            .filter(response -> isExpectedCompletedPayment(response, paymentContext))
                            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS));
            return initialPaymentStateService.complete(customerKey, recovered);
        }

        String rawBillingKey = billingKeyEncryptor.decrypt(paymentContext.encryptedBillingKey());
        TossPaymentsClient.PaymentResponse paymentResponse;
        try {
            paymentResponse = tossPaymentsClient.charge(
                    rawBillingKey,
                    customerKey,
                    paymentContext.amount(),
                    paymentContext.orderId(),
                    paymentContext.planName() + " 구독 결제",
                    paymentContext.chargeIdempotencyKey());
        } catch (BusinessException chargeFailure) {
            paymentResponse = tossPaymentsClient.findPaymentByOrderId(paymentContext.orderId())
                    .filter(response -> isExpectedCompletedPayment(response, paymentContext))
                    .orElseThrow(() -> chargeFailure);
        }

        if (!isExpectedCompletedPayment(paymentResponse, paymentContext)) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        }

        return initialPaymentStateService.complete(customerKey, paymentResponse);
    }

    /** 내 구독 조회 — 구독 없으면 null 반환 (404 아님) */
    @Transactional(readOnly = true)
    public SubscriptionResponse getMySubscription(Long memberId) {
        return subscriptionRepository.findByMemberId(memberId)
                .map(SubscriptionResponse::from)
                .orElse(null);
    }

    /** 구독 해지 (기간 만료 시 실제 해지) */
    @Transactional
    public CancelResponse cancel(Long memberId) {
        Subscription sub = findActiveSubscription(memberId);
        sub.cancel();
        return CancelResponse.from(sub);
    }

    /** 결제 내역 조회 */
    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> getPaymentHistory(Long memberId) {
        subscriptionRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        return paymentHistoryRepository.findByMemberIdWithPlan(memberId)
                .stream()
                .map(PaymentHistoryResponse::from)
                .toList();
    }

    /** 구독 여부 확인 (feature gating용) */
    @Transactional(readOnly = true)
    public boolean isSubscriber(Long memberId) {
        return subscriptionRepository.findByMemberId(memberId)
                .map(Subscription::isActive)
                .orElse(false);
    }

    /** 여러 회원의 구독 여부를 배치 작업에서 한 번에 확인 */
    @Transactional(readOnly = true)
    public Set<Long> getSubscriberMemberIds(List<Long> memberIds) {
        if (memberIds.isEmpty()) return Set.of();

        return Set.copyOf(subscriptionRepository.findSubscriberMemberIds(
                memberIds,
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.CANCELLED,
                LocalDateTime.now()));
    }

    /**
     * 정기결제 배치 처리 (BillingScheduler에서 호출)
     */
    @Transactional
    public void processBilling(Long subscriptionId) {
        Subscription sub = subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 결제 시점 재검증 (#178): 배치 조회~개별 결제 사이에 취소/정지됐거나
        // 이미 결제되어 nextBillingAt이 미래로 갱신된 구독은 과금하지 않는다
        boolean billable = sub.getStatus() == SubscriptionStatus.ACTIVE
                || sub.getStatus() == SubscriptionStatus.PAYMENT_FAILED;
        if (!billable || sub.getNextBillingAt().isAfter(LocalDateTime.now())) {
            log.info("정기결제 스킵: subscriptionId={}, status={}, nextBillingAt={}",
                    subscriptionId, sub.getStatus(), sub.getNextBillingAt());
            return;
        }

        String rawBillingKey = billingKeyEncryptor.decrypt(sub.getBillingKey());
        String orderId       = recurringOrderId(sub);
        LocalDateTime now    = LocalDateTime.now();

        try {
            TossPaymentsClient.PaymentResponse resp =
                    tossPaymentsClient.charge(
                            rawBillingKey,
                            sub.getCustomerKey(),
                            sub.getPlan().getPrice(),
                            orderId,
                            sub.getPlan().getName() + " 구독 정기결제");

            recordSuccessfulBilling(sub, orderId, resp, now);

        } catch (BusinessException e) {
            Optional<TossPaymentsClient.PaymentResponse> approved =
                    tossPaymentsClient.findPaymentByOrderId(orderId)
                            .filter(resp -> resp.paymentKey() != null
                                    && resp.totalAmount() == sub.getPlan().getPrice());
            if (approved.isPresent()) {
                recordSuccessfulBilling(sub, orderId, approved.get(), now);
                log.info("정기결제 승인 결과 복구: subscriptionId={}, orderId={}",
                        subscriptionId, orderId);
                return;
            }

            String reason = e.getMessage();
            sub.paymentFailed(reason);

            PaymentHistory history = PaymentHistory.builder()
                    .member(sub.getMember())
                    .subscription(sub)
                    .tossOrderId(orderId)
                    .amount(sub.getPlan().getPrice())
                    .status(PaymentStatus.FAIL)
                    .billedAt(now)
                    .failReason(reason)
                    .build();
            paymentHistoryRepository.save(history);
            log.warn("정기결제 실패: subscriptionId={}, reason={}", subscriptionId, reason);
        }
    }

    private String recurringOrderId(Subscription sub) {
        return "AUTO-" + sub.getId() + "-"
                + BILLING_CYCLE_FORMAT.format(sub.getNextBillingAt()) + "-"
                + sub.getFailCount();
    }

    private void recordSuccessfulBilling(Subscription sub, String orderId,
                                         TossPaymentsClient.PaymentResponse response,
                                         LocalDateTime billedAt) {
        sub.paymentSuccess(billedAt.plusMonths(1));
        paymentHistoryRepository.save(PaymentHistory.builder()
                .member(sub.getMember())
                .subscription(sub)
                .tossOrderId(orderId)
                .tossPaymentKey(response.paymentKey())
                .amount(sub.getPlan().getPrice())
                .status(PaymentStatus.SUCCESS)
                .billedAt(billedAt)
                .build());
    }

    private void expireUnstartedAttempts(List<BillingAttempt> attempts) {
        LocalDateTime now = LocalDateTime.now();
        attempts.stream()
                .filter(attempt -> attempt.getStatus() == BillingAttemptStatus.PENDING)
                .filter(attempt -> !attempt.getExpiresAt().isAfter(now))
                .filter(attempt -> attempt.getBillingKey() == null && attempt.getOrderId() == null)
                .forEach(BillingAttempt::expire);
    }

    /**
     * 빌링키/주문번호를 저장하기 전에 중단된 시도는 실제 과금 수단이 남아 있지 않다.
     * 충분한 대기 후 FAILED로 종료해 사용자가 새 카드 인증을 시작할 수 있게 한다.
     */
    private void failAbandonedBillingIssueAttempts(List<BillingAttempt> attempts) {
        LocalDateTime cutoff = LocalDateTime.now().minus(ABANDONED_BILLING_ISSUE_TIMEOUT);
        attempts.stream()
                .filter(attempt -> attempt.getStatus() == BillingAttemptStatus.PROCESSING)
                .filter(attempt -> attempt.getBillingKey() == null && attempt.getOrderId() == null)
                .filter(attempt -> attempt.getProcessingAt() != null
                        && attempt.getProcessingAt().isBefore(cutoff))
                .forEach(BillingAttempt::fail);
    }

    private boolean isExpectedCompletedPayment(
            TossPaymentsClient.PaymentResponse paymentResponse,
            InitialPaymentStateService.InitialPaymentContext context) {
        return paymentResponse != null
                && paymentResponse.paymentKey() != null
                && context.orderId().equals(paymentResponse.orderId())
                && context.amount() == paymentResponse.totalAmount()
                && "DONE".equals(paymentResponse.status())
                && "BILLING".equals(paymentResponse.type());
    }

    private boolean isActiveAttempt(BillingAttempt attempt) {
        if (attempt.getStatus() == BillingAttemptStatus.PROCESSING) return true;
        if (attempt.getStatus() != BillingAttemptStatus.PENDING) return false;
        // 기존 복구 필드가 있는 PENDING은 Toss 승인 여부 확인 전까지 차단한다.
        if (attempt.getBillingKey() != null || attempt.getOrderId() != null) return true;
        return attempt.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private Subscription findActiveSubscription(Long memberId) {
        return subscriptionRepository.findByMemberId(memberId)
                .filter(Subscription::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }
}
