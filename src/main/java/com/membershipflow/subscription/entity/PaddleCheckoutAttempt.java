package com.membershipflow.subscription.entity;

import com.membershipflow.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "paddle_checkout_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaddleCheckoutAttempt {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "external_transaction_id", unique = true, length = 64)
    private String externalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaddleCheckoutAttemptStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PaddleCheckoutAttempt(Member member, SubscriptionPlan plan, LocalDateTime now) {
        this.id = UUID.randomUUID().toString();
        this.member = member;
        this.plan = plan;
        this.status = PaddleCheckoutAttemptStatus.PENDING;
        this.expiresAt = now.plusMinutes(30);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void attachTransaction(String transactionId) {
        this.externalTransactionId = transactionId;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isProcessable(LocalDateTime now) {
        return status == PaddleCheckoutAttemptStatus.PENDING && expiresAt.isAfter(now);
    }

    public void complete(LocalDateTime completedAt) {
        this.status = PaddleCheckoutAttemptStatus.COMPLETED;
        this.completedAt = completedAt;
        this.updatedAt = LocalDateTime.now();
    }
}
