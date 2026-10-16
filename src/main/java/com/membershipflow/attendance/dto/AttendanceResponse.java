package com.membershipflow.attendance.dto;

import com.membershipflow.attendance.entity.Attendance;

import java.time.LocalDate;

public record AttendanceResponse (
        LocalDate attendDate,
        int dayNo
) {
    public static AttendanceResponse from(Attendance a){
        return new AttendanceResponse(a.getAttendDate(),
                a.getDayNo());
    }
}
