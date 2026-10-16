package com.membershipflow.attendance.repository;

import com.membershipflow.attendance.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    Optional<Attendance> findByMemberIdAndAttendDate(Long memberId, LocalDate attendDate);
}
