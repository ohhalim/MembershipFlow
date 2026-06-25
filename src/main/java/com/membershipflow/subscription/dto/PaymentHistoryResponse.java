package com.membershipflow.subscription.dto;

import com.membershipflow.subscription.entity.PaymentHistory;
import com.membershipflow.subscription.entity.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentHistoryResponse(
        Long id,
        int amount,
        PaymentStatus status,
        LocalDateTime billedAt,
        String failReason,
        String planName
) {
    public static PaymentHistoryResponse from(PaymentHistory ph) {
        return new PaymentHistoryResponse(
                ph.getId(),
                ph.getAmount(),
                ph.getStatus(),
                ph.getBilledAt(),
                ph.getFailReason(),
                ph.getSubscription().getPlan().getName());
    }
}
