package com.membershipflow.course.repository;

import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MembershipCourseRepository extends JpaRepository<MembershipCourse, Long> {

    Optional<MembershipCourse> findByNameAndCourseTypeAndMembershipType(
            String name, CourseType courseType, MembershipType membershipType);

    // 같은 골프장의 회원권 여러 개(일반/우대/주중 등)를 한 번에 조회 (#141 부가정보 upsert)
    List<MembershipCourse> findAllByNameAndCourseType(String name, CourseType courseType);

    // 활성 코스 전체 조회 (#100) — 여러 서비스에 중복되던 findAll().stream().filter(isActive)를
    // DB 레벨 필터링으로 대체 (완전한 페이지네이션 리팩터링은 별도 스코프)
    List<MembershipCourse> findAllByActiveTrue();

    @Query("""
            SELECT c FROM MembershipCourse c
            WHERE (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.courseType = :courseType)
              AND (:membershipType IS NULL OR c.membershipType = :membershipType)
              AND (:region IS NULL OR c.region = :region)
              AND c.active = true
            """)
    Page<MembershipCourse> search(
            @Param("q") String q,
            @Param("courseType") CourseType courseType,
            @Param("membershipType") MembershipType membershipType,
            @Param("region") String region,
            Pageable pageable);

    @Query("SELECT c FROM MembershipCourse c WHERE c.id IN :ids AND c.active = true")
    List<MembershipCourse> findAllByIdIn(@Param("ids") List<Long> ids);

    // 대표 가격 정렬은 비정규화 latest_price가 아니라 동일한 freshness 계약을 적용한
    // (course, source)별 최신 행 중 최저 행을 사용한다. 대표 가격·source·시각이 같은
    // 물리 행에서 반환되어 목록 카드와 정렬 결과가 어긋나지 않도록 한다.
    @Query(value = """
            SELECT c.id, c.name, c.region, c.course_type, c.membership_type, c.holes,
                   c.active, c.created_at, c.updated_at,
                   rp.representative_price AS latest_price,
                   representative_source.name AS latest_price_source,
                   rp.representative_price_at AS latest_price_at
            FROM membership_course c
            LEFT JOIN (
                SELECT representative.course_id,
                       representative.price AS representative_price,
                       representative.source_id AS representative_source_id,
                       representative.collected_at AS representative_price_at
                FROM (
                    SELECT latest.id, latest.course_id, latest.source_id, latest.price, latest.collected_at,
                           ROW_NUMBER() OVER (
                               PARTITION BY latest.course_id
                               ORDER BY latest.price ASC, latest.collected_at DESC, latest.id DESC
                           ) AS representative_rn
                    FROM (
                        SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at,
                               ROW_NUMBER() OVER (
                                   PARTITION BY ph.course_id, ph.source_id
                                   ORDER BY ph.collected_at DESC, ph.id DESC
                               ) AS source_rn
                        FROM price_history ph
                        JOIN crawl_source cs ON cs.id = ph.source_id
                        JOIN membership_course filtered ON filtered.id = ph.course_id
                        WHERE filtered.active = true
                          AND cs.active = true
                          AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                                   AND CURRENT_TIMESTAMP
                          AND (:q IS NULL OR filtered.name LIKE %:q%)
                          AND (:courseType IS NULL OR filtered.course_type = :courseType)
                          AND (:membershipType IS NULL OR filtered.membership_type = :membershipType)
                          AND (:region IS NULL OR filtered.region = :region)
                    ) latest
                    WHERE latest.source_rn = 1
                ) representative
                WHERE representative.representative_rn = 1
            ) rp ON rp.course_id = c.id
            LEFT JOIN crawl_source representative_source
                ON representative_source.id = rp.representative_source_id
            WHERE c.active = true
              AND (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.course_type = :courseType)
              AND (:membershipType IS NULL OR c.membership_type = :membershipType)
              AND (:region IS NULL OR c.region = :region)
            ORDER BY
                CASE WHEN :sort IN ('price_asc', 'price_desc')
                          AND rp.representative_price IS NULL THEN 1 ELSE 0 END ASC,
                CASE WHEN :sort = 'price_asc'  THEN rp.representative_price END ASC,
                CASE WHEN :sort = 'price_desc' THEN rp.representative_price END DESC,
                CASE WHEN :sort = 'latest'     THEN rp.representative_price_at END DESC,
                c.name ASC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<MembershipCourse> searchWithPriceSort(
            @Param("q") String q,
            @Param("courseType") String courseType,
            @Param("membershipType") String membershipType,
            @Param("region") String region,
            @Param("sort") String sort,
            @Param("size") int size,
            @Param("offset") long offset);

    @Query(value = """
            SELECT COUNT(*)
            FROM membership_course c
            WHERE c.active = true
              AND (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.course_type = :courseType)
              AND (:membershipType IS NULL OR c.membership_type = :membershipType)
              AND (:region IS NULL OR c.region = :region)
            """, nativeQuery = true)
    long countSearch(
            @Param("q") String q,
            @Param("courseType") String courseType,
            @Param("membershipType") String membershipType,
            @Param("region") String region);

    @Query(value = """
            SELECT ranked.course_id, ranked.name, ranked.region,
                   ranked.course_type, ranked.membership_type,
                   ranked.current_price, ranked.base_price,
                   ranked.change_rate, ranked.change_amount
            FROM (
                SELECT c.id AS course_id, c.name, c.region,
                       c.course_type, c.membership_type,
                       current_price.price AS current_price,
                       base.price AS base_price,
                       ROUND((current_price.price - base.price) * 100.0 / base.price, 2) AS change_rate,
                       current_price.price - base.price AS change_amount
                FROM membership_course c
                JOIN (
                    SELECT representative.course_id, representative.price,
                           representative.collected_at
                    FROM (
                        SELECT latest.course_id, latest.price, latest.collected_at, latest.id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY latest.course_id
                                   ORDER BY latest.price ASC, latest.collected_at DESC, latest.id DESC
                               ) AS representative_rn
                        FROM (
                            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY ph.course_id, ph.source_id
                                       ORDER BY ph.collected_at DESC, ph.id DESC
                                   ) AS source_rn
                            FROM price_history ph
                            JOIN crawl_source cs ON cs.id = ph.source_id
                            WHERE cs.active = TRUE
                              AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                                       AND CURRENT_TIMESTAMP
                        ) latest
                        WHERE latest.source_rn = 1
                    ) representative
                    WHERE representative.representative_rn = 1
                ) current_price ON current_price.course_id = c.id
                JOIN (
                    SELECT representative.course_id, representative.price,
                           representative.collected_at
                    FROM (
                        SELECT latest.course_id, latest.price, latest.collected_at, latest.id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY latest.course_id
                                   ORDER BY latest.price ASC,
                                            ABS(TIMESTAMPDIFF(SECOND, latest.collected_at, :baseTime)),
                                            latest.id DESC
                               ) AS representative_rn
                        FROM (
                            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY ph.course_id, ph.source_id
                                       ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                                ph.collected_at DESC, ph.id DESC
                                   ) AS source_rn
                            FROM price_history ph
                            JOIN crawl_source cs ON cs.id = ph.source_id
                            WHERE cs.active = TRUE
                              AND ph.collected_at BETWEEN :searchFrom AND :searchTo
                              AND ph.collected_at <= :baseTime
                        ) latest
                        WHERE latest.source_rn = 1
                    ) representative
                    WHERE representative.representative_rn = 1
                ) base ON base.course_id = c.id
                WHERE c.active = true
                  AND base.price <> 0
                  AND (:courseType IS NULL OR c.course_type = :courseType)
            ) ranked
            WHERE ((:sort = 'LOSS' AND ranked.change_rate < 0)
                OR (:sort <> 'LOSS' AND ranked.change_rate > 0))
            ORDER BY
                CASE WHEN :sort = 'LOSS' THEN ranked.change_rate END ASC,
                CASE WHEN :sort <> 'LOSS' THEN ranked.change_rate END DESC,
                ranked.course_id ASC
            LIMIT :size OFFSET :offset
            """, nativeQuery = true)
    List<Object[]> findRankingPage(
            @Param("baseTime") java.time.LocalDateTime baseTime,
            @Param("searchFrom") java.time.LocalDateTime searchFrom,
            @Param("searchTo") java.time.LocalDateTime searchTo,
            @Param("courseType") String courseType,
            @Param("sort") String sort,
            @Param("size") int size,
            @Param("offset") long offset);

    @Query(value = """
            SELECT COUNT(*)
            FROM (
                SELECT c.id,
                       ROUND((current_price.price - base.price) * 100.0 / base.price, 2) AS change_rate
                FROM membership_course c
                JOIN (
                    SELECT representative.course_id, representative.price
                    FROM (
                        SELECT latest.course_id, latest.price, latest.collected_at, latest.id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY latest.course_id
                                   ORDER BY latest.price ASC, latest.collected_at DESC, latest.id DESC
                               ) AS representative_rn
                        FROM (
                            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY ph.course_id, ph.source_id
                                       ORDER BY ph.collected_at DESC, ph.id DESC
                                   ) AS source_rn
                            FROM price_history ph
                            JOIN crawl_source cs ON cs.id = ph.source_id
                            WHERE cs.active = TRUE
                              AND ph.collected_at BETWEEN DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 48 HOUR)
                                                       AND CURRENT_TIMESTAMP
                        ) latest
                        WHERE latest.source_rn = 1
                    ) representative
                    WHERE representative.representative_rn = 1
                ) current_price ON current_price.course_id = c.id
                JOIN (
                    SELECT representative.course_id, representative.price
                    FROM (
                        SELECT latest.course_id, latest.price, latest.collected_at, latest.id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY latest.course_id
                                   ORDER BY latest.price ASC,
                                            ABS(TIMESTAMPDIFF(SECOND, latest.collected_at, :baseTime)),
                                            latest.id DESC
                               ) AS representative_rn
                        FROM (
                            SELECT ph.id, ph.course_id, ph.source_id, ph.price, ph.collected_at,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY ph.course_id, ph.source_id
                                       ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                                ph.collected_at DESC, ph.id DESC
                                   ) AS source_rn
                            FROM price_history ph
                            JOIN crawl_source cs ON cs.id = ph.source_id
                            WHERE cs.active = TRUE
                              AND ph.collected_at BETWEEN :searchFrom AND :searchTo
                              AND ph.collected_at <= :baseTime
                        ) latest
                        WHERE latest.source_rn = 1
                    ) representative
                    WHERE representative.representative_rn = 1
                ) base ON base.course_id = c.id
                WHERE c.active = true
                  AND base.price <> 0
                  AND (:courseType IS NULL OR c.course_type = :courseType)
            ) ranked
            WHERE ((:sort = 'LOSS' AND ranked.change_rate < 0)
                OR (:sort <> 'LOSS' AND ranked.change_rate > 0))
            """, nativeQuery = true)
    long countRanking(
            @Param("baseTime") java.time.LocalDateTime baseTime,
            @Param("searchFrom") java.time.LocalDateTime searchFrom,
            @Param("searchTo") java.time.LocalDateTime searchTo,
            @Param("courseType") String courseType,
            @Param("sort") String sort);
}
