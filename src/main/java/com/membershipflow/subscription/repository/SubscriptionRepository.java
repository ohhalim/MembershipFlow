package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByMemberId(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Subscription s WHERE s.status IN :statuses AND s.nextBillingAt <= :now")
    List<Subscription> findDueForBillingWithLock(
            @Param("statuses") List<SubscriptionStatus> statuses,
            @Param("now") LocalDateTime now);
}
