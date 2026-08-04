package com.membershipflow.price.dto;

import java.time.LocalDateTime;

public record LatestSourcePriceResponse(
        String sourceName,
        String sourceUrl,
        Long price,
        LocalDateTime collectedAt,
        boolean fresh
) {
    /** 기존 호출부와의 소스 호환성을 위한 생성자. */
    public LatestSourcePriceResponse(String sourceName, String sourceUrl,
                                     Long price, LocalDateTime collectedAt) {
        this(sourceName, sourceUrl, price, collectedAt, true);
    }
}
