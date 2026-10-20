package com.membershipflow.attendance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class AttendanceEventPeriod {

    private final LocalDate startDate;
    private final LocalDate endDate;

    public AttendanceEventPeriod(
            @Value("${attendance.event.start-date}") LocalDate startDate,
            @Value("${attendance.event.end-date}") LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("attendance event end-date must not be before start-date");
        }
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
