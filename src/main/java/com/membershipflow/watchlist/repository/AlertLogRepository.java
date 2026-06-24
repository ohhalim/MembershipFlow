package com.membershipflow.watchlist.repository;

import com.membershipflow.watchlist.entity.AlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

    /** 24시간 내 동일 watchlist 알림 발송 여부 */
    boolean existsByWatchlistIdAndSentAtAfter(Long watchlistId, LocalDateTime after);

    /** 회원의 알림 이력 (최신순, watchlist→course 페치) */
    @Query("""
            SELECT a FROM AlertLog a
            JOIN FETCH a.watchlist w
            JOIN FETCH w.course
            WHERE w.member.id = :memberId
            ORDER BY a.sentAt DESC
            """)
    List<AlertLog> findByMemberIdOrderBySentAtDesc(@Param("memberId") Long memberId);
}
