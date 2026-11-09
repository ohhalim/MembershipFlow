package com.membershipflow.attendance.dto;

public record AttendanceStatusResponse(
        boolean attendedToday,
        int dayNo,
        long couponCount,
        boolean couponLimitReached,
        boolean inPeriod
) {
}
