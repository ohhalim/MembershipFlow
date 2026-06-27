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

    @Query(value = """
            SELECT c.id, c.name, c.region, c.course_type, c.membership_type, c.holes,
                   c.active, c.created_at, c.updated_at
            FROM membership_course c
            LEFT JOIN (
                SELECT ph.course_id, ph.price, ph.collected_at,
                       ROW_NUMBER() OVER (PARTITION BY ph.course_id ORDER BY ph.collected_at DESC) AS rn
                FROM price_history ph
            ) latest ON c.id = latest.course_id AND latest.rn = 1
            WHERE c.active = true
              AND (:q IS NULL OR c.name LIKE %:q%)
              AND (:courseType IS NULL OR c.course_type = :courseType)
              AND (:membershipType IS NULL OR c.membership_type = :membershipType)
              AND (:region IS NULL OR c.region = :region)
            ORDER BY
                CASE WHEN :sort = 'price_asc'  THEN latest.price END ASC,
                CASE WHEN :sort = 'price_desc' THEN latest.price END DESC,
                CASE WHEN :sort = 'latest'     THEN latest.collected_at END DESC,
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
