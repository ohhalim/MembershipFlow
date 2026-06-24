package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.BillingAttempt;
import com.membershipflow.subscription.entity.BillingAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface BillingAttemptRepository extends JpaRepository<BillingAttempt, Long> {
    Optional<BillingAttempt> findByCustomerKeyAndStatusAndExpiresAtAfter(
            String customerKey, BillingAttemptStatus status, LocalDateTime now);
}
