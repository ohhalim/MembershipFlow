package com.membershipflow.attendance.dto;

import com.membershipflow.attendance.entity.Attendance;
import com.membershipflow.attendance.service.AttendResult;

import java.time.LocalDate;

public record AttendanceResponse (
        LocalDate attendDate,
        int dayNo,
        boolean streakBroken
) {
    public static AttendanceResponse from(AttendResult result){
        Attendance a = result.attendance();
        return new AttendanceResponse(a.getAttendDate(),
                a.getDayNo(),
                result.streakBroken());
    }
}
