package com.membershipflow.subscription.entity;

import com.membershipflow.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(name = "toss_order_id", nullable = false, unique = true, length = 64)
    private String tossOrderId;

    @Column(name = "toss_payment_key", length = 200)
    private String tossPaymentKey;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "billed_at", nullable = false)
    private LocalDateTime billedAt;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    @Builder
    public PaymentHistory(Member member, Subscription subscription,
                          String tossOrderId, String tossPaymentKey,
                          int amount, PaymentStatus status,
                          LocalDateTime billedAt, String failReason) {
        this.member         = member;
        this.subscription   = subscription;
        this.tossOrderId    = tossOrderId;
        this.tossPaymentKey = tossPaymentKey;
        this.amount         = amount;
        this.status         = status;
        this.billedAt       = billedAt;
        this.failReason     = failReason;
    }
}
