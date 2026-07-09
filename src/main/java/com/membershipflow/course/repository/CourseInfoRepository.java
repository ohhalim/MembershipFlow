package com.membershipflow.course.repository;

import com.membershipflow.course.entity.CourseInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CourseInfoRepository extends JpaRepository<CourseInfo, Long> {

    Optional<CourseInfo> findByCourseId(Long courseId);
}
