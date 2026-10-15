package com.membershipflow.attendance.service;

import com.membershipflow.attendance.entity.Attendance;
import com.membershipflow.attendance.repository.AttendanceRepository;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;

import com.membershipflow.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionService subscriptionService;

    @Transactional
    public Attendance attend(Long memberId) {
        return attend(memberId, LocalDate.now(SERVICE_ZONE));
    }

    Attendance attend(Long memberId, LocalDate today) {
        if (subscriptionService.isSubscriber(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Optional<Attendance> todays =
                attendanceRepository.findByMemberIdAndAttendDate(memberId, today);
        if (todays.isPresent()) {
            return todays.get();
        }
        int dayNo = attendanceRepository.findByMemberIdAndAttendDate(
                memberId, today.minusDays(1))
                .map(yesterday -> yesterday.getDayNo() < 7 ? yesterday.getDayNo() + 1 : 1)
                .orElse(1);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND)); 
        return attendanceRepository.save(Attendance.builder()
                .member(member)
                .attendDate(today)
                .dayNo(dayNo)
                .build());
    }

}
