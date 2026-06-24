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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public BillingAttempt(Member member, SubscriptionPlan plan, String customerKey) {
        this.member      = member;
        this.plan        = plan;
        this.customerKey = customerKey;
        this.status      = BillingAttemptStatus.PENDING;
        this.expiresAt   = LocalDateTime.now().plusMinutes(30);
        this.createdAt   = LocalDateTime.now();
    }

    public void complete() { this.status = BillingAttemptStatus.COMPLETED; }
    public void fail()     { this.status = BillingAttemptStatus.FAILED; }
}
