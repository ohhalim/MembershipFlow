package com.membershipflow.collect.repository;

import com.membershipflow.collect.entity.CourseSourceMapping;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.course.entity.MembershipCourse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CourseSourceMappingRepository extends JpaRepository<CourseSourceMapping, Long> {

    Optional<CourseSourceMapping> findByCourseAndSource(MembershipCourse course, CrawlSource source);

    Optional<CourseSourceMapping> findBySourceAndSourceKey(CrawlSource source, String sourceKey);
}
