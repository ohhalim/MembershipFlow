package com.membershipflow.price.dto;

import java.time.LocalDateTime;

public record LatestSourcePriceResponse(
        String sourceName,
        String sourceUrl,
        Long price,
        LocalDateTime collectedAt
) {}
