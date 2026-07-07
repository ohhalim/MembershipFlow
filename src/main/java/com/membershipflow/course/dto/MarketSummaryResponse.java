package com.membershipflow.course.dto;

public record MarketSummaryResponse(
        long updatedToday,
        int risers,
        int fallers
) {}
