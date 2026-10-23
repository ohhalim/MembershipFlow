package com.membershipflow.attendance.service;

import com.membershipflow.attendance.dto.AttendanceStatusResponse;
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
import org.mockito.Spy;
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
    private static final LocalDate EVENT_START = LocalDate.of(2026, 10, 20);
    private static final LocalDate EVENT_END = LocalDate.of(2026, 11, 20);

    @Mock
    AttendanceRepository attendanceRepository;
    @Mock
    MemberRepository memberRepository;
    @Mock
    SubscriptionService subscriptionService;
    @Mock
    CouponRepository couponRepository;
    @Spy
    AttendanceEvent event = new AttendanceEvent(EVENT_CODE, EVENT_START, EVENT_END);
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
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getDayNo()).isEqualTo(1);
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
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getDayNo()).isEqualTo(1);
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
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance()).isSameAs(todays);
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
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getDayNo()).isEqualTo(7);
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
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance()).isSameAs(todays);
    }

    @Test
    @DisplayName("이벤트 시작 전에는 출석할 수 없다")
    void rejectBeforeEventStart() {
        // given
        LocalDate today = EVENT_START.minusDays(1);

        // when, then
        assertThatThrownBy(() -> attendanceService.attend(MEMBER_ID, today))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTENDANCE_EVENT_NOT_IN_PERIOD);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("이벤트 종료일(00:00 종료)에는 출석할 수 없다")
    void rejectOnEventEndDate() {
        // given
        LocalDate today = EVENT_END;

        // when, then
        assertThatThrownBy(() -> attendanceService.attend(MEMBER_ID, today))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ATTENDANCE_EVENT_NOT_IN_PERIOD);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("이벤트 종료 전날에는 출석할 수 있다")
    void attendOnLastEventDay() {
        // given
        LocalDate today = EVENT_END.minusDays(1);

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.empty());
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getAttendDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("참여자 수는 그날 출석 기록 수다")
    void countParticipantsByDate() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        given(attendanceRepository.countByAttendDate(today)).willReturn(42L);

        // when
        long result = attendanceService.countParticipants(today);

        // then
        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("3일째에서 끊긴 뒤 첫 출석이면 끊김 안내를 담는다")
    void streakBrokenAfterThirdDay() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        Attendance last = attendanceOn(today.minusDays(3), 3);

        givenNewAttendance(today);
        given(attendanceRepository.findTopByMemberIdAndAttendDateBeforeOrderByAttendDateDesc(MEMBER_ID, today))
                .willReturn(Optional.of(last));

        // when
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getDayNo()).isEqualTo(1);
        assertThat(result.streakBroken()).isTrue();
    }

    @Test
    @DisplayName("7일을 채운 다음 날 1일째는 끊김이 아니다")
    void notBrokenAfterCompletingRound() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        Attendance yesterday = attendanceOn(today.minusDays(1), 7);

        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.of(yesterday));
        given(attendanceRepository.findTopByMemberIdAndAttendDateBeforeOrderByAttendDateDesc(MEMBER_ID, today))
                .willReturn(Optional.of(yesterday));
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.attendance().getDayNo()).isEqualTo(1);
        assertThat(result.streakBroken()).isFalse();
    }

    @Test
    @DisplayName("첫 출석은 끊김이 아니다")
    void notBrokenOnFirstAttendance() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        givenNewAttendance(today);

        // when
        AttendResult result = attendanceService.attend(MEMBER_ID, today);

        // then
        assertThat(result.streakBroken()).isFalse();
    }

    @Test
    @DisplayName("오늘 출석했으면 상태는 오늘 일차다")
    void statusAttendedToday() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today))
                .willReturn(Optional.of(attendanceOn(today, 4)));

        // when
        AttendanceStatusResponse status = attendanceService.status(MEMBER_ID, today);

        // then
        assertThat(status.attendedToday()).isTrue();
        assertThat(status.dayNo()).isEqualTo(4);
    }

    @Test
    @DisplayName("오늘 출석 전이면 어제까지의 연속 일수다")
    void statusContinuingFromYesterday() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1)))
                .willReturn(Optional.of(attendanceOn(today.minusDays(1), 3)));

        // when
        AttendanceStatusResponse status = attendanceService.status(MEMBER_ID, today);

        // then
        assertThat(status.attendedToday()).isFalse();
        assertThat(status.dayNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("어제 기록이 없거나 어제 7일을 채웠으면 0일째다")
    void statusZeroWhenBrokenOrCompleted() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        LocalDate other = LocalDate.of(2026, 11, 3);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, other)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, other.minusDays(1)))
                .willReturn(Optional.of(attendanceOn(other.minusDays(1), 7)));

        // when
        AttendanceStatusResponse broken = attendanceService.status(MEMBER_ID, today);
        AttendanceStatusResponse completed = attendanceService.status(MEMBER_ID, other);

        // then
        assertThat(broken.dayNo()).isZero();
        assertThat(completed.dayNo()).isZero();
    }

    @Test
    @DisplayName("이번 이벤트 할인권 2장이면 모두 받음이다")
    void statusCouponLimitReached() {
        // given
        LocalDate today = LocalDate.of(2026, 10, 27);
        given(couponRepository.countByMemberIdAndEventCode(MEMBER_ID, EVENT_CODE)).willReturn(2L);

        // when
        AttendanceStatusResponse status = attendanceService.status(MEMBER_ID, today);

        // then
        assertThat(status.couponCount()).isEqualTo(2);
        assertThat(status.couponLimitReached()).isTrue();
        assertThat(status.inPeriod()).isTrue();
    }

    @Test
    @DisplayName("이벤트 종료일에는 기간 밖으로 표시한다")
    void statusOutOfPeriod() {
        // when
        AttendanceStatusResponse status = attendanceService.status(MEMBER_ID, EVENT_END);

        // then
        assertThat(status.inPeriod()).isFalse();
    }

    private Attendance attendanceOn(LocalDate date, int dayNo) {
        return Attendance.builder()
                .member(member)
                .attendDate(date)
                .dayNo(dayNo)
                .build();
    }

    private void givenNewAttendance(LocalDate today) {
        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today)).willReturn(Optional.empty());
        given(attendanceRepository.findByMemberIdAndAttendDate(MEMBER_ID, today.minusDays(1))).willReturn(Optional.empty());
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(attendanceRepository.save(any(Attendance.class))).willAnswer(invocation -> invocation.getArgument(0));
    }
}
