package com.membershipflow.course.dto;

import java.util.List;

public record CourseListItemResponse(
        Long id,
        String name,
        String region,
        String category,
        String membershipType,
        Integer holes,
        Long latestPrice,
        String updatedAt,
        Double changeRate,
        List<SourcePriceItem> sourcePrices
) {
    public record SourcePriceItem(String source, Long price) {}
}
