package com.membershipflow.watchlist.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record WatchlistAddRequest(
        @NotNull Long courseId,
        @Min(0) Long targetPrice,
        boolean alertYn
) {}
