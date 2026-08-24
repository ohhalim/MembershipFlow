package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaddleCheckoutAttemptRepository extends JpaRepository<PaddleCheckoutAttempt, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaddleCheckoutAttempt> findFirstByMemberIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            Long memberId, PaddleCheckoutAttemptStatus status, LocalDateTime expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaddleCheckoutAttempt a JOIN FETCH a.member JOIN FETCH a.plan WHERE a.id = :id")
    Optional<PaddleCheckoutAttempt> findByIdForUpdate(@Param("id") String id);
}
