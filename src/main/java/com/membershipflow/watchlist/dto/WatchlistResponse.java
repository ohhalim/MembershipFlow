package com.membershipflow.watchlist.dto;

import com.membershipflow.watchlist.entity.Watchlist;

import java.time.LocalDateTime;

public record WatchlistResponse(
        Long id,
        Long courseId,
        String courseName,
        String region,
        Long targetPrice,
        boolean alertYn,
        Long latestPrice,
        LocalDateTime createdAt
) {
    public static WatchlistResponse of(Watchlist w, Long latestPrice) {
        return new WatchlistResponse(
                w.getId(),
                w.getCourse().getId(),
                w.getCourse().getName(),
                w.getCourse().getRegion(),
                w.getTargetPrice(),
                w.isAlertYn(),
                latestPrice,
                w.getCreatedAt());
    }
}
