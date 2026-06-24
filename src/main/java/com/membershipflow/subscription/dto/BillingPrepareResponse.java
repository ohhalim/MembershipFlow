package com.membershipflow.subscription.dto;

public record BillingPrepareResponse(
        String customerKey,
        String clientKey,
        Long planId
) {}
