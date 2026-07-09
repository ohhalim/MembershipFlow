package com.membershipflow.price.repository;

import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.price.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {

    boolean existsByCourseAndSourceAndCollectedAt(MembershipCourse course, CrawlSource source, LocalDateTime collectedAt);

    // 소스별 최신가 — ROW_NUMBER() 윈도우 함수로 tie-break 포함
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT id, course_id, source_id, price, collected_at, collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY source_id ORDER BY collected_at DESC, id DESC) AS rn
                FROM price_history
                WHERE course_id = :courseId
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findLatestBySource(@Param("courseId") Long courseId);

    // 여러 종목의 소스별 최신가 배치 조회 (목록 N+1 방지)
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT id, course_id, source_id, price, collected_at, collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY collected_at DESC, id DESC) AS rn
                FROM price_history
                WHERE course_id IN (:courseIds)
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findLatestByCourseIds(@Param("courseIds") List<Long> courseIds);

    // 7일 전 배치 조회 (priceChangeRate 계산용)
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT id, course_id, source_id, price, collected_at, collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY ABS(TIMESTAMPDIFF(SECOND, collected_at, :baseTime)), id DESC) AS rn
                FROM price_history
                WHERE course_id IN (:courseIds)
                  AND collected_at BETWEEN :from AND :to
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findNearestToBatchByTime(
            @Param("courseIds") List<Long> courseIds,
            @Param("baseTime") LocalDateTime baseTime,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // 차트 — DAY 집계
    @Query(value = """
            SELECT DATE(collected_at) as date,
                   ROUND(AVG(price)) as avgPrice,
                   MIN(price) as minPrice,
                   MAX(price) as maxPrice,
                   COUNT(*) as cnt
            FROM price_history
            WHERE course_id = :courseId
              AND collected_at BETWEEN :from AND :to
            GROUP BY DATE(collected_at)
            ORDER BY date
            """, nativeQuery = true)
    List<Object[]> findChartByDay(
            @Param("courseId") Long courseId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // 차트 — WEEK 집계
    @Query(value = """
            SELECT DATE(MIN(collected_at)) as date,
                   ROUND(AVG(price)) as avgPrice,
                   MIN(price) as minPrice,
                   MAX(price) as maxPrice,
                   COUNT(*) as cnt
            FROM price_history
            WHERE course_id = :courseId
              AND collected_at BETWEEN :from AND :to
            GROUP BY YEARWEEK(collected_at, 1)
            ORDER BY date
            """, nativeQuery = true)
    List<Object[]> findChartByWeek(
            @Param("courseId") Long courseId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // 차트 — MONTH 집계
    @Query(value = """
            SELECT DATE(MIN(collected_at)) as date,
                   ROUND(AVG(price)) as avgPrice,
                   MIN(price) as minPrice,
                   MAX(price) as maxPrice,
                   COUNT(*) as cnt
            FROM price_history
            WHERE course_id = :courseId
              AND collected_at BETWEEN :from AND :to
            GROUP BY DATE_FORMAT(collected_at, '%Y-%m')
            ORDER BY date
            """, nativeQuery = true)
    List<Object[]> findChartByMonth(
            @Param("courseId") Long courseId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    // 랭킹용 현재가 (소스 무관 최신가)
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT id, course_id, source_id, price, collected_at, collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY collected_at DESC, id DESC) AS rn
                FROM price_history
                WHERE course_id IN (:courseIds)
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findCurrentPriceForRanking(@Param("courseIds") List<Long> courseIds);

    // 여러 종목의 (종목, 소스)별 최신가 배치 조회 — 목록 거래소별 가격 표시용
    @Query(value = """
            SELECT ph.course_id, cs.name, ph.price
            FROM (
                SELECT course_id, source_id, price,
                       ROW_NUMBER() OVER (PARTITION BY course_id, source_id ORDER BY collected_at DESC, id DESC) AS rn
                FROM price_history
                WHERE course_id IN (:courseIds)
            ) ph
            JOIN crawl_source cs ON cs.id = ph.source_id
            WHERE ph.rn = 1
            ORDER BY ph.course_id, cs.name
            """, nativeQuery = true)
    List<Object[]> findLatestPerSourceByCourseIds(@Param("courseIds") List<Long> courseIds);

    // 특정 시점 이후 가격이 갱신된 종목 수 (시장 요약용)
    @Query("SELECT COUNT(DISTINCT ph.course.id) FROM PriceHistory ph WHERE ph.collectedAt >= :since")
    long countCoursesUpdatedSince(@Param("since") LocalDateTime since);

    // 랭킹용 기준 시점 가격 (period 시작 시점 가장 근접 레코드)
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT id, course_id, source_id, price, collected_at, collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY ABS(TIMESTAMPDIFF(SECOND, collected_at, :baseTime)), id DESC) AS rn
                FROM price_history
                WHERE course_id IN (:courseIds)
                  AND collected_at BETWEEN :searchFrom AND :searchTo
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findBasePriceForRanking(
            @Param("courseIds") List<Long> courseIds,
            @Param("baseTime") LocalDateTime baseTime,
            @Param("searchFrom") LocalDateTime searchFrom,
            @Param("searchTo") LocalDateTime searchTo);
}
