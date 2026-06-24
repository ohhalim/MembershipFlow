package com.membershipflow.watchlist.repository;

import com.membershipflow.watchlist.entity.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    @Query("SELECT w FROM Watchlist w JOIN FETCH w.course WHERE w.member.id = :memberId ORDER BY w.createdAt DESC")
    List<Watchlist> findByMemberIdWithCourse(@Param("memberId") Long memberId);

    Optional<Watchlist> findByMemberIdAndCourseId(Long memberId, Long courseId);

    boolean existsByMemberIdAndCourseId(Long memberId, Long courseId);

    long countByMemberId(Long memberId);

    /** 알림 활성화된 watchlist 전체 (AlertService 배치 체크용) */
    @Query("SELECT w FROM Watchlist w JOIN FETCH w.course JOIN FETCH w.member WHERE w.alertYn = true AND w.targetPrice IS NOT NULL")
    List<Watchlist> findAllAlertEnabled();
}
