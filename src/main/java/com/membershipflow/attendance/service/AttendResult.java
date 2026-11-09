package com.membershipflow.attendance.service;

import com.membershipflow.attendance.entity.Attendance;

public record AttendResult(
        Attendance attendance,
        boolean streakBroken
) {
}
