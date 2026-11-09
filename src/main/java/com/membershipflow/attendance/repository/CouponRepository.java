package com.membershipflow.attendance.repository;

import com.membershipflow.attendance.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    long countByMemberIdAndEventCode(Long memberId, String eventCode);
}
