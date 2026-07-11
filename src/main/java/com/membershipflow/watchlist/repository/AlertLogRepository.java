package com.membershipflow.watchlist.repository;

import com.membershipflow.watchlist.entity.AlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface AlertLogRepository extends JpaRepository<AlertLog, Long> {

    /** 지정된 관심종목 중 기준 시각 이후 알림이 발송된 관심종목 ID */
    @Query("""
            SELECT DISTINCT a.watchlist.id FROM AlertLog a
            WHERE a.watchlist.id IN :watchlistIds
              AND a.sentAt > :after
            """)
    List<Long> findWatchlistIdsSentAfter(
            @Param("watchlistIds") Collection<Long> watchlistIds,
            @Param("after") LocalDateTime after);

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
