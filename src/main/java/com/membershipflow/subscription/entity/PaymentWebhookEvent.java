package com.membershipflow.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_webhook_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", nullable = false, length = 20)
    private PaymentProvider paymentProvider;

    @Column(name = "external_event_id", nullable = false, length = 64)
    private String externalEventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public PaymentWebhookEvent(PaymentProvider paymentProvider, String externalEventId,
                               String eventType, LocalDateTime occurredAt) {
        this.paymentProvider = paymentProvider;
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.processedAt = LocalDateTime.now();
    }
}
