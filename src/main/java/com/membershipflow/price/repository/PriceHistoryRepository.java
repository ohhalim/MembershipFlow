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

    // 현재가 freshness 정책: 수집 주기가 하루인 소스의 일시적인 지연을 허용하되,
    // 48시간을 넘긴 가격은 현재가/알림/대표가 계산에서 제외한다.
    // collected_at은 과거 시세 수집일도 함께 저장하므로, 반드시 현재 시각 상한도 둔다.
    // 소스별 최신가 — ROW_NUMBER() 윈도우 함수로 tie-break 포함
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id,
                       ROW_NUMBER() OVER (PARTITION BY ph.source_id ORDER BY ph.collected_at DESC, ph.id DESC) AS rn
                FROM price_history ph
                JOIN crawl_source cs ON cs.id = ph.source_id
                WHERE ph.course_id = :courseId
                  AND cs.active = TRUE
                  AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                           AND CURRENT_TIMESTAMP
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findLatestBySource(@Param("courseId") Long courseId);

    // 여러 종목의 대표 최신가 배치 조회 (목록 N+1 방지)
    // 1) 활성 소스의 유효기간 내 가격만 남김
    // 2) (course, source)별 최신 행 선택
    // 3) course별 최저가 행 하나를 대표 행으로 선택
    // 대표 가격·source·collectedAt이 같은 물리 행에서 반환되도록 한다.
    @Query(value = """
            SELECT representative.id, representative.course_id, representative.source_id,
                   representative.price, representative.collected_at, representative.collect_run_id
            FROM (
                SELECT latest.id, latest.course_id, latest.source_id, latest.price,
                       latest.collected_at, latest.collect_run_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY latest.course_id
                           ORDER BY latest.price ASC, latest.collected_at DESC, latest.id DESC
                       ) AS representative_rn
                FROM (
                    SELECT ph.id, ph.course_id, ph.source_id, ph.price,
                           ph.collected_at, ph.collect_run_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY ph.course_id, ph.source_id
                               ORDER BY ph.collected_at DESC, ph.id DESC
                           ) AS source_rn
                    FROM price_history ph
                    JOIN crawl_source cs ON cs.id = ph.source_id
                    WHERE ph.course_id IN (:courseIds)
                      AND cs.active = TRUE
                      AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                               AND CURRENT_TIMESTAMP
                ) latest
                WHERE latest.source_rn = 1
            ) representative
            WHERE representative.representative_rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findLatestByCourseIds(@Param("courseIds") List<Long> courseIds);

    // 여러 종목의 (종목, 소스)별 최신 PriceHistory 배치 조회 — 목표가 알림의 최저가 선택용
    // 비활성 소스·48시간 초과·미래 수집시각은 현재가 계산에서 제외한다.
    @Query(value = """
            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id
            FROM (
                SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at, ph.collect_run_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY ph.course_id, ph.source_id
                           ORDER BY ph.collected_at DESC, ph.id DESC
                       ) AS rn
                FROM price_history ph
                JOIN crawl_source cs ON cs.id = ph.source_id
                WHERE ph.course_id IN (:courseIds)
                  AND cs.active = TRUE
                  AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                           AND CURRENT_TIMESTAMP
            ) ph
            WHERE ph.rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findLatestPerSourceEntitiesByCourseIds(
            @Param("courseIds") List<Long> courseIds);

    // 7일 전 배치 조회 (priceChangeRate 계산용)
    @Query(value = """
            SELECT representative.id, representative.course_id, representative.source_id,
                   representative.price, representative.collected_at, representative.collect_run_id
            FROM (
                SELECT latest.id, latest.course_id, latest.source_id, latest.price,
                       latest.collected_at, latest.collect_run_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY latest.course_id
                           ORDER BY latest.price ASC,
                                    ABS(TIMESTAMPDIFF(SECOND, latest.collected_at, :baseTime)),
                                    latest.id DESC
                       ) AS representative_rn
                FROM (
                    SELECT ph.id, ph.course_id, ph.source_id, ph.price,
                           ph.collected_at, ph.collect_run_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY ph.course_id, ph.source_id
                               ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                        ph.collected_at DESC, ph.id DESC
                           ) AS source_rn
                    FROM price_history ph
                    JOIN crawl_source cs ON cs.id = ph.source_id
                    WHERE ph.course_id IN (:courseIds)
                      AND cs.active = TRUE
                      AND ph.collected_at BETWEEN :from AND :to
                      AND ph.collected_at <= :baseTime
                ) latest
                WHERE latest.source_rn = 1
            ) representative
            WHERE representative.representative_rn = 1
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

    // 랭킹용 현재가 — 목록/상세와 동일한 대표 행을 사용한다.
    default List<PriceHistory> findCurrentPriceForRanking(List<Long> courseIds) {
        return findLatestByCourseIds(courseIds);
    }

    // 여러 종목의 (종목, 소스)별 최신가 배치 조회 — 목록 거래소별 가격 표시용
    @Query(value = """
            SELECT ph.course_id, cs.name, ph.price, ph.collected_at
            FROM (
                SELECT ph.course_id, ph.source_id, ph.price,
                       ph.collected_at,
                       ROW_NUMBER() OVER (
                           PARTITION BY ph.course_id, ph.source_id
                           ORDER BY ph.collected_at DESC, ph.id DESC
                       ) AS rn
                FROM price_history ph
                JOIN crawl_source active_source ON active_source.id = ph.source_id
                WHERE ph.course_id IN (:courseIds)
                  AND active_source.active = TRUE
                  AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                           AND CURRENT_TIMESTAMP
            ) ph
            JOIN crawl_source cs ON cs.id = ph.source_id
            WHERE ph.rn = 1
            ORDER BY ph.course_id, cs.name
            """, nativeQuery = true)
    List<Object[]> findLatestPerSourceByCourseIds(@Param("courseIds") List<Long> courseIds);

    // 특정 시점 이후 가격이 갱신된 종목 수 (시장 요약용)
    @Query("""
            SELECT COUNT(DISTINCT ph.course.id)
            FROM PriceHistory ph
            JOIN ph.source source
            WHERE source.active = true
              AND ph.collectedAt >= :since
              AND ph.collectedAt <= CURRENT_TIMESTAMP
            """)
    long countCoursesUpdatedSince(@Param("since") LocalDateTime since);

    // 랭킹용 기준 시점 가격.
    // 기준시각 이후 행을 선택하지 않으며, 소스별 기준가 중 최저가 행을 반환한다.
    @Query(value = """
            SELECT representative.id, representative.course_id, representative.source_id,
                   representative.price, representative.collected_at, representative.collect_run_id
            FROM (
                SELECT latest.id, latest.course_id, latest.source_id, latest.price,
                       latest.collected_at, latest.collect_run_id,
                       ROW_NUMBER() OVER (
                           PARTITION BY latest.course_id
                           ORDER BY latest.price ASC,
                                    ABS(TIMESTAMPDIFF(SECOND, latest.collected_at, :baseTime)),
                                    latest.id DESC
                       ) AS representative_rn
                FROM (
                    SELECT ph.id, ph.course_id, ph.source_id, ph.price,
                           ph.collected_at, ph.collect_run_id,
                           ROW_NUMBER() OVER (
                               PARTITION BY ph.course_id, ph.source_id
                               ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                        ph.collected_at DESC, ph.id DESC
                           ) AS source_rn
                    FROM price_history ph
                    JOIN crawl_source cs ON cs.id = ph.source_id
                    WHERE ph.course_id IN (:courseIds)
                      AND cs.active = TRUE
                      AND ph.collected_at BETWEEN :searchFrom AND :searchTo
                      AND ph.collected_at <= :baseTime
                ) latest
                WHERE latest.source_rn = 1
            ) representative
            WHERE representative.representative_rn = 1
            """, nativeQuery = true)
    List<PriceHistory> findBasePriceForRanking(
            @Param("courseIds") List<Long> courseIds,
            @Param("baseTime") LocalDateTime baseTime,
            @Param("searchFrom") LocalDateTime searchFrom,
            @Param("searchTo") LocalDateTime searchTo);
}
