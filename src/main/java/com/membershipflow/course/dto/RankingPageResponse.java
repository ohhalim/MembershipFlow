package com.membershipflow.course.dto;

import java.util.List;

public record RankingPageResponse(
        List<RankingItemResponse> content,
        int page,
        int size,
        long totalElements,
        boolean hasNext
) {}
