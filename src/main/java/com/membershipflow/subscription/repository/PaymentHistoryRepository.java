package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    @Query("""
            SELECT ph FROM PaymentHistory ph
            JOIN FETCH ph.subscription s
            JOIN FETCH s.plan
            WHERE ph.member.id = :memberId
            ORDER BY ph.billedAt DESC
            """)
    List<PaymentHistory> findByMemberIdWithPlan(@Param("memberId") Long memberId);
}
