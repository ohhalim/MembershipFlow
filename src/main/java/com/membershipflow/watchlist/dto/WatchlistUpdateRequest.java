package com.membershipflow.watchlist.dto;

import jakarta.validation.constraints.Min;

public record WatchlistUpdateRequest(
        @Min(0) Long targetPrice,
        boolean alertYn
) {}
