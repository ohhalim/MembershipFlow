package com.membershipflow.course.controller;

import com.membershipflow.course.dto.CourseDetailResponse;
import com.membershipflow.course.dto.CourseListItemResponse;
import com.membershipflow.course.dto.RankingItemResponse;
import com.membershipflow.course.dto.RankingPageResponse;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public ResponseEntity<Page<CourseListItemResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CourseType courseType,
            @RequestParam(required = false) MembershipType membershipType,
            @RequestParam(required = false) String region,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int clampedSize = Math.min(size, 100);
        PageRequest pageable = PageRequest.of(page, clampedSize, Sort.by("name").ascending());
        return ResponseEntity.ok(courseService.search(q, courseType, membershipType, region, pageable));
    }

    // /courses/ranking 이 /courses/{courseId} 보다 먼저 위치해야 경로 충돌 없음
    @GetMapping("/ranking")
    public ResponseEntity<RankingPageResponse> ranking(
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(defaultValue = "GAIN") String sort,
            @RequestParam(required = false) CourseType courseType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int clampedSize = Math.min(size, 50);
        return ResponseEntity.ok(courseService.getRanking(period, sort, courseType, page, clampedSize));
    }

    @GetMapping("/{courseId}")
    public ResponseEntity<CourseDetailResponse> detail(@PathVariable Long courseId) {
        return ResponseEntity.ok(courseService.getDetail(courseId));
    }
}
