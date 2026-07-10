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

    /** AES-256 암호화 저장 */
    @Column(name = "billing_key", nullable = false, length = 500)
    private String billingKey;

    @Column(name = "customer_key", nullable = false, unique = true, length = 300)
    private String customerKey;

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

    public void cancel() {
        this.status      = SubscriptionStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
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
        this.billingKey       = billingKey;
        this.customerKey      = customerKey;
        this.cardNumberMasked = cardNumberMasked;
        this.cardCompany      = cardCompany;
        this.failCount        = 0;
        this.startedAt        = startedAt;
        this.nextBillingAt    = nextBillingAt;
        this.cancelledAt      = null;
        this.updatedAt        = LocalDateTime.now();
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
        return status == SubscriptionStatus.ACTIVE
                || (status == SubscriptionStatus.CANCELLED && LocalDateTime.now().isBefore(nextBillingAt));
    }
}
