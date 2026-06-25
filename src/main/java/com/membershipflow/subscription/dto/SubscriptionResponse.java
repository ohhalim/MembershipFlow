package com.membershipflow.subscription.dto;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;

import java.time.LocalDateTime;

public record SubscriptionResponse(
        Long id,
        PlanDto plan,
        SubscriptionStatus status,
        LocalDateTime startedAt,
        LocalDateTime nextBillingAt,
        String cardNumberMasked,
        String cardCompany,
        LocalDateTime cancelledAt
) {
    public record PlanDto(Long id, String code, String name, int price) {}

    public static SubscriptionResponse from(Subscription s) {
        return new SubscriptionResponse(
                s.getId(),
                new PlanDto(s.getPlan().getId(), s.getPlan().getCode(),
                        s.getPlan().getName(), s.getPlan().getPrice()),
                s.getStatus(),
                s.getStartedAt(),
                s.getNextBillingAt(),
                s.getCardNumberMasked(),
                s.getCardCompany(),
                s.getCancelledAt());
    }
}
