package com.membershipflow.subscription.dto;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;

import java.time.LocalDateTime;

public record CancelResponse(
        Long id,
        SubscriptionStatus status,
        LocalDateTime cancelledAt,
        LocalDateTime serviceEndsAt
) {
    public static CancelResponse from(Subscription s) {
        return new CancelResponse(s.getId(), s.getStatus(), s.getCancelledAt(), s.getNextBillingAt());
    }
}
