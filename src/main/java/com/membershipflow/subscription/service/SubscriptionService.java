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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final DateTimeFormatter BILLING_CYCLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final MemberRepository              memberRepository;
    private final SubscriptionPlanRepository    planRepository;
    private final BillingAttemptRepository      billingAttemptRepository;
    private final SubscriptionRepository        subscriptionRepository;
    private final PaymentHistoryRepository      paymentHistoryRepository;
    private final TossPaymentsClient            tossPaymentsClient;
    private final BillingKeyEncryptor           billingKeyEncryptor;

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
        Member member = findMember(memberId);
        SubscriptionPlan plan = planRepository.findByIdAndActiveTrue(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 이미 활성 구독이면 신규 등록 불가
        subscriptionRepository.findByMemberId(memberId).ifPresent(sub -> {
            if (sub.isActive()) throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        });

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
    @Transactional
    public SubscriptionResponse handleCallback(String customerKey, String authKey) {
        BillingAttempt attempt = billingAttemptRepository
                .findByCustomerKeyAndStatusAndExpiresAtAfter(
                        customerKey, BillingAttemptStatus.PENDING, LocalDateTime.now())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        attempt.complete();

        Member member           = attempt.getMember();
        SubscriptionPlan plan   = attempt.getPlan();

        // 재구독 확인 (#179): member_id UNIQUE 제약이 있어 신규 INSERT는 기존 row가
        // 있으면 실패한다. 토스 결제는 롤백이 안 되므로 반드시 결제 호출 전에 확인.
        Optional<Subscription> existing = subscriptionRepository.findByMemberId(member.getId());
        if (existing.isPresent() && existing.get().isActive()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        }

        // 빌링 키 발급
        TossPaymentsClient.BillingKeyResponse billingKeyResp =
                tossPaymentsClient.issueBillingKey(customerKey, authKey);

        String encryptedBillingKey = billingKeyEncryptor.encrypt(billingKeyResp.billingKey());

        // 최초 결제
        String orderId  = "ORDER-" + UUID.randomUUID();
        TossPaymentsClient.PaymentResponse paymentResp =
                tossPaymentsClient.charge(
                        billingKeyResp.billingKey(),
                        customerKey,
                        plan.getPrice(),
                        orderId,
                        plan.getName() + " 구독 결제");

        LocalDateTime now           = LocalDateTime.now();
        LocalDateTime nextBillingAt = now.plusMonths(1);
        String cardNumberMasked = billingKeyResp.card() != null ? billingKeyResp.card().number() : null;
        String cardCompany      = billingKeyResp.card() != null ? billingKeyResp.card().cardCompany() : null;

        // 기존 row가 있으면(취소 후 만료된 재구독) INSERT 대신 재활성화 (#179)
        Subscription subscription;
        if (existing.isPresent()) {
            subscription = existing.get();
            subscription.resubscribe(plan, encryptedBillingKey, customerKey,
                    cardNumberMasked, cardCompany, now, nextBillingAt);
        } else {
            subscription = Subscription.builder()
                    .member(member)
                    .plan(plan)
                    .billingKey(encryptedBillingKey)
                    .customerKey(customerKey)
                    .cardNumberMasked(cardNumberMasked)
                    .cardCompany(cardCompany)
                    .startedAt(now)
                    .nextBillingAt(nextBillingAt)
                    .build();
            subscriptionRepository.save(subscription);
        }

        PaymentHistory history = PaymentHistory.builder()
                .member(member)
                .subscription(subscription)
                .tossOrderId(orderId)
                .tossPaymentKey(paymentResp.paymentKey())
                .amount(plan.getPrice())
                .status(PaymentStatus.SUCCESS)
                .billedAt(now)
                .build();
        paymentHistoryRepository.save(history);

        return SubscriptionResponse.from(subscription);
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

    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private Subscription findActiveSubscription(Long memberId) {
        return subscriptionRepository.findByMemberId(memberId)
                .filter(Subscription::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }
}
