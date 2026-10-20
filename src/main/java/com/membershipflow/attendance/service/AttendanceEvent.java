package com.membershipflow.attendance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class AttendanceEvent {

    private final String code;
    private final LocalDate startDate;
    private final LocalDate endDate;

    public AttendanceEvent(
            @Value("${attendance.event.code}") String code,
            @Value("${attendance.event.start-date}") LocalDate startDate,
            @Value("${attendance.event.end-date}") LocalDate endDate) {
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("attendance event end-date must be after start-date");
        }
        this.code = code;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public String code() {
        return code;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && date.isBefore(endDate);
    }
}
