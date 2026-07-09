package com.membershipflow.course.dto;

import java.util.List;

public record SourceComparisonItem(
        Long courseId,
        String name,
        String region,
        String courseType,
        List<SourcePricePoint> prices,   // 이 코스에 가격이 있는 모든 소스 (2개 이상)
        long minPrice,
        long maxPrice,
        long diffAmount,   // maxPrice - minPrice (원 단위)
        double diffRate    // 차이 비율 (%)
) {
    public record SourcePricePoint(String sourceName, long price) {}
}
