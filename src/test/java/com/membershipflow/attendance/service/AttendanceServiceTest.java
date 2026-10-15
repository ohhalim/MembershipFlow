package com.membershipflow.attendance.service;

import com.membershipflow.attendance.entity.Attendance;
import com.membershipflow.attendance.repository.AttendanceRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {
    private static final long MEMBER_ID = 1L;

    @Mock
    AttendanceRepository attendanceRepository;
    @Mock
    MemberRepository memberRepository;
    @Mock
    SubscriptionService subscriptionService;
    @InjectMocks
    AttendanceService attendanceService;

    Member member;

    @BeforeEach
    void setUp() {
        member = Member.builder().id(MEMBER_ID).email("a@a.com").build();
    }

    @Test
    @DisplayName("어제가 7일째면 오늘은 1일째로 저장한다")
    void resetAfterSeventhDay() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        Attendance yesterday = Attendance.builder()
                .member(member)
                .attendDate(today.minusDays(1))
                .dayNo(7)
                .build();

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.of(yesterday));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Attendance result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.getDayNo()).isEqualTo(1);
    }
}
