package com.membershipflow.course.dto;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;

import java.time.LocalDateTime;
import java.util.List;

public record CourseDetailResponse(
        Long id,
        String name,
        String region,
        CourseType courseType,
        MembershipType membershipType,
        Integer holes,
        List<SourcePrice> latestPrices,
        boolean watchlisted,
        Long targetPrice
) {
    public record SourcePrice(
            String sourceName,
            String sourceUrl,
            Long price,
            LocalDateTime collectedAt
    ) {}
}
