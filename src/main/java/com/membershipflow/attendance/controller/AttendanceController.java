package com.membershipflow.attendance.controller;

import com.membershipflow.attendance.dto.AttendanceResponse;
import com.membershipflow.attendance.service.AttendanceService;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendances")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceService attendanceService;

    @PostMapping
    public ResponseEntity<AttendanceResponse> attend(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return ResponseEntity.ok(AttendanceResponse.from(
                attendanceService.attend(principal
                        .getMember()
                        .getId()
                )
        ));
    }
}
