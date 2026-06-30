package com.membershipflow.course.dto;

public record SourceComparisonItem(
        Long courseId,
        String name,
        String region,
        String courseType,
        Long dongaPrice,
        Long dongbuPrice,
        Long diffAmount,   // dongbu - donga (원 단위)
        Double diffRate    // 차이 비율 (%)
) {}
