package com.membershipflow.course.dto;

public record CourseListItemResponse(
        Long id,
        String name,
        String region,
        String category,
        String membershipType,
        Integer holes,
        Long latestPrice,
        String updatedAt,
        Double changeRate
) {}
