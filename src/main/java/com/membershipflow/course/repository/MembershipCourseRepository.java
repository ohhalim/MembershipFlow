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

    // (#100) price_history를 매번 JOIN하던 것을 membership_course.latest_price(_at) 비정규화 컬럼으로
    // 직접 정렬하도록 단순화 — CollectService가 수집 시 최신값을 갱신해 둔다
    @Query(value = """
            SELECT c.id, c.name, c.region, c.course_type, c.membership_type, c.holes,
                   c.active, c.created_at, c.updated_at,
                   c.latest_price, c.latest_price_source, c.latest_price_at
            FROM membership_course c
            LEFT JOIN (
                SELECT latest.course_id, MIN(latest.price) AS representative_price
                FROM (
                    SELECT ph.course_id, ph.source_id, ph.price,
                           ROW_NUMBER() OVER (
                               PARTITION BY ph.course_id, ph.source_id
                               ORDER BY ph.collected_at DESC, ph.id DESC
                           ) AS rn
                    FROM price_history ph
                    JOIN membership_course filtered ON filtered.id = ph.course_id
                    WHERE filtered.active = true
                      AND (:q IS NULL OR filtered.name LIKE %:q%)
                      AND (:courseType IS NULL OR filtered.course_type = :courseType)
                      AND (:membershipType IS NULL OR filtered.membership_type = :membershipType)
                      AND (:region IS NULL OR filtered.region = :region)
                ) latest
                WHERE latest.rn = 1
                GROUP BY latest.course_id
            ) rp ON rp.course_id = c.id
            WHERE c.active = true
              AND (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.course_type = :courseType)
              AND (:membershipType IS NULL OR c.membership_type = :membershipType)
              AND (:region IS NULL OR c.region = :region)
            ORDER BY
                CASE WHEN :sort = 'price_asc'  THEN rp.representative_price END ASC,
                CASE WHEN :sort = 'price_desc' THEN rp.representative_price END DESC,
                CASE WHEN :sort = 'latest'     THEN c.latest_price_at END DESC,
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
                       c.latest_price AS current_price,
                       base.price AS base_price,
                       ROUND((c.latest_price - base.price) * 100.0 / base.price, 2) AS change_rate,
                       c.latest_price - base.price AS change_amount
                FROM membership_course c
                JOIN (
                    SELECT candidate.course_id, candidate.price
                    FROM (
                        SELECT ph.course_id, ph.price,
                               ROW_NUMBER() OVER (
                                   PARTITION BY ph.course_id
                                   ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                            ph.id DESC
                               ) AS rn
                        FROM price_history ph
                        WHERE ph.collected_at BETWEEN :searchFrom AND :searchTo
                    ) candidate
                    WHERE candidate.rn = 1
                ) base ON base.course_id = c.id
                WHERE c.active = true
                  AND c.latest_price IS NOT NULL
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
                       ROUND((c.latest_price - base.price) * 100.0 / base.price, 2) AS change_rate
                FROM membership_course c
                JOIN (
                    SELECT candidate.course_id, candidate.price
                    FROM (
                        SELECT ph.course_id, ph.price,
                               ROW_NUMBER() OVER (
                                   PARTITION BY ph.course_id
                                   ORDER BY ABS(TIMESTAMPDIFF(SECOND, ph.collected_at, :baseTime)),
                                            ph.id DESC
                               ) AS rn
                        FROM price_history ph
                        WHERE ph.collected_at BETWEEN :searchFrom AND :searchTo
                    ) candidate
                    WHERE candidate.rn = 1
                ) base ON base.course_id = c.id
                WHERE c.active = true
                  AND c.latest_price IS NOT NULL
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
