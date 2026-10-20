package com.membershipflow.attendance.service;

import com.membershipflow.attendance.entity.Attendance;
import com.membershipflow.attendance.entity.Coupon;
import com.membershipflow.attendance.repository.AttendanceRepository;
import com.membershipflow.attendance.repository.CouponRepository;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AttendanceServiceTest {
    private static final long MEMBER_ID = 1L;
    private static final String EVENT_CODE = "ATTENDANCE_2026_FALL";

    @Mock
    AttendanceRepository attendanceRepository;
    @Mock
    MemberRepository memberRepository;
    @Mock
    SubscriptionService subscriptionService;
    @Mock
    CouponRepository couponRepository;
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

    @Test
    @DisplayName("어제 출석 기록이 없으면 1일째로 저장한다")
    void saveFirstDayWhenNoYesterday() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.empty());
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Attendance result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.getDayNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 이미 출석했으면 저장하지 않고 기존 기록을 반환한다")
    void returnExistingWhenAlreadyAttended() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);

        Attendance todays = Attendance.builder()
                .member(member)
                .attendDate(today)
                .dayNo(3)
                .build();

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.of(todays));

        // when
        Attendance result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result).isSameAs(todays);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("7일째 출석하면 할인권 1장을 발급한다")
    void issueCouponOnSeventhDay() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        Attendance yesterday = Attendance.builder()
                .member(member)
                .attendDate(today.minusDays(1))
                .dayNo(6)
                .build();

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.of(yesterday));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        Attendance result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.getDayNo()).isEqualTo(7);
        ArgumentCaptor<Coupon> captor = ArgumentCaptor.forClass(Coupon.class);
        verify(couponRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getEventCode()).isEqualTo(EVENT_CODE);
    }

    @Test
    @DisplayName("할인권 2장을 이미 받았으면 출석할 수 없다")
    void rejectWhenCouponLimitReached() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(couponRepository.countByMemberIdAndEventCode(MEMBER_ID, EVENT_CODE)).willReturn(2L);

        // when, then
        assertThatThrownBy(() -> attendanceService.attend(MEMBER_ID, today))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COUPON_LIMIT_REACHED);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("할인권 2장을 받았어도 오늘 이미 출석했으면 기존 기록을 반환한다")
    void returnExistingWhenAlreadyAttendedEvenIfCouponLimitReached() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        Attendance todays = Attendance.builder()
                .member(member)
                .attendDate(today)
                .dayNo(7)
                .build();

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.of(todays));
        lenient().when(couponRepository.countByMemberIdAndEventCode(MEMBER_ID, EVENT_CODE)).thenReturn(2L);

        // when
        Attendance result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result).isSameAs(todays);
    }
}
