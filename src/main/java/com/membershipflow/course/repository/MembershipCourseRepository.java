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
            WHERE c.active = true
              AND (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.course_type = :courseType)
              AND (:membershipType IS NULL OR c.membership_type = :membershipType)
              AND (:region IS NULL OR c.region = :region)
            ORDER BY
                CASE WHEN :sort = 'price_asc'  THEN c.latest_price END ASC,
                CASE WHEN :sort = 'price_desc' THEN c.latest_price END DESC,
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
}
