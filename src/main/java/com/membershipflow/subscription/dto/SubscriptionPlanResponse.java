package com.membershipflow.subscription.dto;

import com.membershipflow.subscription.entity.SubscriptionPlan;
import com.membershipflow.subscription.entity.BillingCycle;

public record SubscriptionPlanResponse(
        Long id,
        String code,
        String name,
        int price,
        BillingCycle billingCycle,
        String description
) {
    public static SubscriptionPlanResponse from(SubscriptionPlan p) {
        return new SubscriptionPlanResponse(
                p.getId(), p.getCode(), p.getName(), p.getPrice(),
                p.getBillingCycle(), p.getDescription());
    }
}
