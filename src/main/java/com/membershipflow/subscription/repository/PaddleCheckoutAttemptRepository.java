package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.PaddleCheckoutAttempt;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.time.LocalDateTime;
import com.membershipflow.subscription.entity.PaddleCheckoutAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaddleCheckoutAttemptRepository extends JpaRepository<PaddleCheckoutAttempt, String> {

    boolean existsByMemberIdAndStatusAndExpiresAtAfter(
            Long memberId, PaddleCheckoutAttemptStatus status, LocalDateTime expiresAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaddleCheckoutAttempt a JOIN FETCH a.member JOIN FETCH a.plan WHERE a.id = :id")
    Optional<PaddleCheckoutAttempt> findByIdForUpdate(@Param("id") String id);
}
