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
        String latestPriceSource,
        List<SourcePriceItem> sourcePrices
) {
    public CourseListItemResponse(Long id, String name, String region,
                                  String category, String membershipType, Integer holes,
                                  Long latestPrice, String updatedAt, Double changeRate,
                                  List<SourcePriceItem> sourcePrices) {
        this(id, name, region, category, membershipType, holes,
                latestPrice, updatedAt, changeRate, null, sourcePrices);
    }

    public record SourcePriceItem(
            String source,
            Long price,
            String collectedAt,
            boolean fresh
    ) {
        public SourcePriceItem(String source, Long price) {
            this(source, price, null, true);
        }
    }
}
