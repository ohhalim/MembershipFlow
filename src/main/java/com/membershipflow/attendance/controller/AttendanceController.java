package com.membershipflow.attendance.controller;

import com.membershipflow.attendance.dto.AttendanceResponse;
import com.membershipflow.attendance.dto.AttendanceStatusResponse;
import com.membershipflow.attendance.dto.ParticipantCountResponse;
import com.membershipflow.attendance.service.AttendResult;
import com.membershipflow.attendance.service.AttendanceService;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
        Long memberId = principal.getMemberId();
        AttendResult result;
        try {
            result = attendanceService.attend(memberId);
        } catch (DataIntegrityViolationException e) {
            result = attendanceService.attend(memberId);
        }
        return ResponseEntity.ok(AttendanceResponse.from(result));
    }

    @GetMapping("/me")
    public ResponseEntity<AttendanceStatusResponse> myStatus(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        return ResponseEntity.ok(attendanceService.status(principal.getMemberId()));
    }

    @GetMapping("/participants/today")
    public ResponseEntity<ParticipantCountResponse> todayParticipants() {
        return ResponseEntity.ok(new ParticipantCountResponse(attendanceService.countTodayParticipants()));
    }
}
