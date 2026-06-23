package com.membershipflow.course.dto;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;

public record RankingItemResponse(
        int rank,
        Long courseId,
        String name,
        String region,
        CourseType courseType,
        MembershipType membershipType,
        Long currentPrice,
        Long basePrice,
        double changeRate,
        long changeAmount
) {}
