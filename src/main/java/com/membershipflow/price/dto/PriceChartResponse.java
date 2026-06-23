package com.membershipflow.price.dto;

import java.time.LocalDate;
import java.util.List;

public record PriceChartResponse(
        Long courseId,
        String courseName,
        String interval,
        LocalDate from,
        LocalDate to,
        List<PricePointDto> points,
        Summary summary,
        boolean subscriptionRequired
) {
    public record Summary(
            Long currentPrice,
            Long basePrice,
            Long changeAmount,
            Double changeRate,
            Long minPrice,
            Long maxPrice
    ) {}
}
