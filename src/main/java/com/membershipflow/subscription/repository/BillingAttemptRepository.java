package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.BillingAttempt;
import com.membershipflow.subscription.entity.BillingAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BillingAttemptRepository extends JpaRepository<BillingAttempt, Long> {
    Optional<BillingAttempt> findByCustomerKey(String customerKey);

    List<BillingAttempt> findAllByMemberIdAndStatusInOrderByIdAsc(
            Long memberId, Collection<BillingAttemptStatus> statuses);

    Optional<BillingAttempt> findByCustomerKeyAndStatusAndExpiresAtAfter(
            String customerKey, BillingAttemptStatus status, LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT ba FROM BillingAttempt ba
            JOIN FETCH ba.member
            JOIN FETCH ba.plan
            WHERE ba.customerKey = :customerKey
            """)
    Optional<BillingAttempt> findByCustomerKeyForUpdate(
            @Param("customerKey") String customerKey);
}
