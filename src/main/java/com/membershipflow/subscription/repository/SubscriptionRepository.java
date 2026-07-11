package com.membershipflow.subscription.repository;

import com.membershipflow.subscription.entity.Subscription;
import com.membershipflow.subscription.entity.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByMemberId(Long memberId);

    @Query("""
            SELECT s.member.id FROM Subscription s
            WHERE s.member.id IN :memberIds
              AND (s.status = :activeStatus
                   OR (s.status = :cancelledStatus AND s.nextBillingAt > :now))
            """)
    List<Long> findSubscriberMemberIds(
            @Param("memberIds") Collection<Long> memberIds,
            @Param("activeStatus") SubscriptionStatus activeStatus,
            @Param("cancelledStatus") SubscriptionStatus cancelledStatus,
            @Param("now") LocalDateTime now);

    // 비관적 락 없음 (#178): 트랜잭션 밖(스케줄러)에서 락 쿼리를 실행하면
    // TransactionRequiredException으로 배치 자체가 죽고, 트랜잭션을 걸어도 조회 직후
    // 종료되면 락이 풀려 무의미하다. 결제 시점 재검증은 processBilling() 내부 가드가 담당.
    @Query("SELECT s FROM Subscription s WHERE s.status IN :statuses AND s.nextBillingAt <= :now")
    List<Subscription> findDueForBilling(
            @Param("statuses") List<SubscriptionStatus> statuses,
            @Param("now") LocalDateTime now);
}
