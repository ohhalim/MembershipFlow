package com.membershipflow.watchlist.dto;

import com.membershipflow.watchlist.entity.AlertLog;

import java.time.LocalDateTime;

public record AlertResponse(
        Long id,
        Long courseId,
        String courseName,
        Long triggeredPrice,
        Long targetPrice,
        String sourceName,
        LocalDateTime sentAt,
        LocalDateTime readAt
) {
    public static AlertResponse from(AlertLog log) {
        return new AlertResponse(
                log.getId(),
                log.getWatchlist().getCourse().getId(),
                log.getWatchlist().getCourse().getName(),
                log.getTriggeredPrice(),
                log.getWatchlist().getTargetPrice(),
                log.getSource().getName(),
                log.getSentAt(),
                log.getReadAt());
    }
}
