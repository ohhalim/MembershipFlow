package com.membershipflow.subscription.entity;

import com.membershipflow.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscription")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", nullable = false, length = 20)
    private PaymentProvider paymentProvider;

    /** AES-256 암호화 저장 */
    @Column(name = "billing_key", length = 500)
    private String billingKey;

    @Column(name = "customer_key", unique = true, length = 300)
    private String customerKey;

    @Column(name = "external_customer_id", length = 64)
    private String externalCustomerId;

    @Column(name = "external_subscription_id", unique = true, length = 64)
    private String externalSubscriptionId;

    @Column(name = "external_updated_at")
    private LocalDateTime externalUpdatedAt;

    @Column(name = "card_number_masked", length = 50)
    private String cardNumberMasked;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "next_billing_at", nullable = false)
    private LocalDateTime nextBillingAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Subscription(Member member, SubscriptionPlan plan,
                        String billingKey, String customerKey,
                        String cardNumberMasked, String cardCompany,
                        LocalDateTime startedAt, LocalDateTime nextBillingAt) {
        this.member          = member;
        this.plan            = plan;
        this.status          = SubscriptionStatus.ACTIVE;
        this.paymentProvider = PaymentProvider.TOSS;
        this.billingKey      = billingKey;
        this.customerKey     = customerKey;
        this.cardNumberMasked = cardNumberMasked;
        this.cardCompany     = cardCompany;
        this.failCount       = 0;
        this.startedAt       = startedAt;
        this.nextBillingAt   = nextBillingAt;
        this.createdAt       = LocalDateTime.now();
        this.updatedAt       = LocalDateTime.now();
    }

    public static Subscription paddle(Member member, SubscriptionPlan plan,
                                      String externalCustomerId, String externalSubscriptionId,
                                      LocalDateTime startedAt, LocalDateTime nextBillingAt) {
        Subscription subscription = new Subscription();
        subscription.member = member;
        subscription.plan = plan;
        subscription.status = SubscriptionStatus.ACTIVE;
        subscription.paymentProvider = PaymentProvider.PADDLE;
        subscription.externalCustomerId = externalCustomerId;
        subscription.externalSubscriptionId = externalSubscriptionId;
        subscription.externalUpdatedAt = startedAt;
        subscription.failCount = 0;
        subscription.startedAt = startedAt;
        subscription.nextBillingAt = nextBillingAt;
        subscription.createdAt = LocalDateTime.now();
        subscription.updatedAt = LocalDateTime.now();
        return subscription;
    }

    public void cancel() {
        this.status      = SubscriptionStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
    }

    public void schedulePaddleCancellation(LocalDateTime serviceEndsAt,
                                           LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.nextBillingAt = serviceEndsAt;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void syncPaddleActive(LocalDateTime nextBillingAt,
                                 LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.status = SubscriptionStatus.ACTIVE;
        this.failCount = 0;
        if (nextBillingAt != null) this.nextBillingAt = nextBillingAt;
        this.cancelledAt = null;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void syncPaddlePaymentFailed(LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.failCount++;
        this.status = failCount >= 3
                ? SubscriptionStatus.SUSPENDED
                : SubscriptionStatus.PAYMENT_FAILED;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void syncPaddlePastDue(LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.status = SubscriptionStatus.PAYMENT_FAILED;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void syncPaddleSuspended(LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.status = SubscriptionStatus.SUSPENDED;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    public void syncPaddleCanceled(LocalDateTime canceledAt,
                                   LocalDateTime externalUpdatedAt) {
        if (isStaleExternalEvent(externalUpdatedAt)) return;
        this.status = SubscriptionStatus.CANCELLED;
        this.cancelledAt = canceledAt;
        this.nextBillingAt = canceledAt;
        this.externalUpdatedAt = externalUpdatedAt;
        this.updatedAt = LocalDateTime.now();
    }

    private boolean isStaleExternalEvent(LocalDateTime occurredAt) {
        return externalUpdatedAt != null && occurredAt.isBefore(externalUpdatedAt);
    }

    /**
     * 재구독 (#179): member_id UNIQUE 제약 때문에 신규 INSERT 대신
     * 기존 row를 새 빌링 정보로 재활성화한다.
     */
    public void resubscribe(SubscriptionPlan plan, String billingKey, String customerKey,
                            String cardNumberMasked, String cardCompany,
                            LocalDateTime startedAt, LocalDateTime nextBillingAt) {
        this.plan             = plan;
        this.status           = SubscriptionStatus.ACTIVE;
        this.paymentProvider  = PaymentProvider.TOSS;
        this.billingKey       = billingKey;
        this.customerKey      = customerKey;
        this.externalCustomerId = null;
        this.externalSubscriptionId = null;
        this.externalUpdatedAt = null;
        this.cardNumberMasked = cardNumberMasked;
        this.cardCompany      = cardCompany;
        this.failCount        = 0;
        this.startedAt        = startedAt;
        this.nextBillingAt    = nextBillingAt;
        this.cancelledAt      = null;
        this.updatedAt        = LocalDateTime.now();
    }

    public void resubscribeWithPaddle(SubscriptionPlan plan, String externalCustomerId,
                                      String externalSubscriptionId,
                                      LocalDateTime startedAt, LocalDateTime nextBillingAt) {
        this.plan = plan;
        this.status = SubscriptionStatus.ACTIVE;
        this.paymentProvider = PaymentProvider.PADDLE;
        this.billingKey = null;
        this.customerKey = null;
        this.externalCustomerId = externalCustomerId;
        this.externalSubscriptionId = externalSubscriptionId;
        this.externalUpdatedAt = startedAt;
        this.cardNumberMasked = null;
        this.cardCompany = null;
        this.failCount = 0;
        this.startedAt = startedAt;
        this.nextBillingAt = nextBillingAt;
        this.cancelledAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    public void paymentSuccess(LocalDateTime nextBillingAt) {
        this.status        = SubscriptionStatus.ACTIVE;
        this.failCount     = 0;
        this.nextBillingAt = nextBillingAt;
        this.updatedAt     = LocalDateTime.now();
    }

    public void paymentFailed(String reason) {
        this.failCount++;
        this.status    = failCount >= 3 ? SubscriptionStatus.SUSPENDED : SubscriptionStatus.PAYMENT_FAILED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return isActiveAt(LocalDateTime.now());
    }

    public boolean isActiveAt(LocalDateTime now) {
        return status == SubscriptionStatus.ACTIVE
                || (status == SubscriptionStatus.CANCELLED && now.isBefore(nextBillingAt));
    }
}
