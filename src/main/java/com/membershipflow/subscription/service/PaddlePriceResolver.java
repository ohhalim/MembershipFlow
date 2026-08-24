package com.membershipflow.subscription.service;

import com.membershipflow.subscription.entity.BillingCycle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaddlePriceResolver {

    private final String monthlyPriceId;
    private final String yearlyPriceId;

    public PaddlePriceResolver(
            @Value("${paddle.monthly-price-id}") String monthlyPriceId,
            @Value("${paddle.yearly-price-id}") String yearlyPriceId) {
        this.monthlyPriceId = monthlyPriceId;
        this.yearlyPriceId = yearlyPriceId;
    }

    public String resolve(BillingCycle billingCycle) {
        return switch (billingCycle) {
            case MONTHLY -> monthlyPriceId;
            case ANNUAL -> yearlyPriceId;
        };
    }
}
