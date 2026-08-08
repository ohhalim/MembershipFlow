package com.membershipflow.subscription.entity;

import java.time.LocalDateTime;

public enum BillingCycle {
    MONTHLY(1),
    ANNUAL(12);

    private final int months;

    BillingCycle(int months) {
        this.months = months;
    }

    public LocalDateTime nextBillingAt(LocalDateTime billedAt) {
        return billedAt.plusMonths(months);
    }
}
