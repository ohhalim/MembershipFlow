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
    private static final int EVENT_DAYS = 7;
    private static final int COUPON_DISCOUNT_RATE = 10;
    private static final int COUPON_LIMIT = 2;

    private final AttendanceRepository attendanceRepository;
    private final MemberRepository memberRepository;
    private final SubscriptionService subscriptionService;
    private final CouponRepository couponRepository;
    private final AttendanceEvent event;

    @Transactional
    public Attendance attend(Long memberId) {
        return attend(memberId, LocalDate.now(SERVICE_ZONE));
    }

    Attendance attend(Long memberId, LocalDate today) {
        if (!event.contains(today)) {
            throw new BusinessException(ErrorCode.ATTENDANCE_EVENT_NOT_IN_PERIOD);
        }
        if (subscriptionService.isSubscriber(memberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        Optional<Attendance> todays =
                attendanceRepository.findByMemberIdAndAttendDate(memberId, today);

        if (todays.isPresent()) {
            return todays.get();
        }
        if (couponRepository.countByMemberIdAndEventCode(memberId, event.code()) >= COUPON_LIMIT) {
            throw new BusinessException(ErrorCode.COUPON_LIMIT_REACHED);
        }

        int dayNo = attendanceRepository.findByMemberIdAndAttendDate(
                memberId, today.minusDays(1))
                .map(yesterday -> yesterday.getDayNo() < EVENT_DAYS ? yesterday.getDayNo() + 1 : 1)
                .orElse(1);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        Attendance saved = attendanceRepository.save(Attendance.builder()
                .member(member)
                .attendDate(today)
                .dayNo(dayNo)
                .build());

        if (dayNo == EVENT_DAYS) {
            couponRepository.save(Coupon.builder()
                    .member(member)
                    .attendance(saved)
                    .discountRate(COUPON_DISCOUNT_RATE)
                    .eventCode(event.code())
                    .build());
        }
        return saved;
    }
}
