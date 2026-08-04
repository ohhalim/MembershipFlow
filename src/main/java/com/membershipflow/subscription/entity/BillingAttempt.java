package com.membershipflow.subscription.entity;

import com.membershipflow.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "customer_key", nullable = false, unique = true, length = 300)
    private String customerKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingAttemptStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 외부 결제 요청을 선점한 시각. PROCESSING은 승인 결과 확인 전까지 유지한다. */
    @Column(name = "processing_at")
    private LocalDateTime processingAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "billing_key", length = 500)
    private String billingKey;

    @Column(name = "order_id", unique = true, length = 64)
    private String orderId;

    @Column(name = "card_number_masked", length = 50)
    private String cardNumberMasked;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Builder
    public BillingAttempt(Member member, SubscriptionPlan plan, String customerKey) {
        this.member      = member;
        this.plan        = plan;
        this.customerKey = customerKey;
        this.status      = BillingAttemptStatus.PENDING;
        this.expiresAt   = LocalDateTime.now().plusMinutes(30);
        this.createdAt   = LocalDateTime.now();
    }

    public void startProcessing() {
        if (this.status != BillingAttemptStatus.PENDING) {
            throw new IllegalStateException("결제 시도는 PENDING 상태에서만 선점할 수 있습니다.");
        }
        this.status = BillingAttemptStatus.PROCESSING;
        this.processingAt = LocalDateTime.now();
    }

    public void complete() {
        this.status = BillingAttemptStatus.COMPLETED;
        this.processingAt = null;
    }

    public void fail() {
        this.status = BillingAttemptStatus.FAILED;
        this.processingAt = null;
    }

    /** 빌링 키 발급과 외부 결제가 시작되지 않은 만료 시도만 정리한다. */
    public void expire() {
        if (this.status != BillingAttemptStatus.PENDING
                || this.billingKey != null
                || this.orderId != null) {
            throw new IllegalStateException("외부 결제 정보가 있는 시도는 자동 만료할 수 없습니다.");
        }
        this.status = BillingAttemptStatus.EXPIRED;
    }

    public void storeBillingKey(String billingKey, String orderId,
                                String cardNumberMasked, String cardCompany) {
        if (this.status != BillingAttemptStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING 상태에서만 빌링 키를 저장할 수 있습니다.");
        }
        if (this.billingKey != null) return;
        this.billingKey = billingKey;
        this.orderId = orderId;
        this.cardNumberMasked = cardNumberMasked;
        this.cardCompany = cardCompany;
    }
}
