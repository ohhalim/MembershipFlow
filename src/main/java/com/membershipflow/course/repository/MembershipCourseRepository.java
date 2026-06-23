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
}
