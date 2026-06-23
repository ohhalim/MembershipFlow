package com.membershipflow.course.dto;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;

import java.time.LocalDateTime;

public record CourseListItemResponse(
        Long id,
        String name,
        String region,
        CourseType courseType,
        MembershipType membershipType,
        Integer holes,
        Long latestPrice,
        LocalDateTime latestCollectedAt,
        Double priceChangeRate
) {}
