package com.membershipflow.course.dto;

public record MarketSummaryResponse(
        long updatedToday,
        int risers,
        int fallers,
        int comparedCourses,   // 2개 이상 거래소에 가격이 있는 활성 종목 수
        double maxSpreadRate    // 그 종목들 중 (max-min)/min*100 의 최댓값, 없으면 0
) {}
