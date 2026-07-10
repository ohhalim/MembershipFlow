package com.membershipflow.course.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "membership_course",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_course_name_type_membership",
        columnNames = {"name", "course_type", "membership_type"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipCourse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "course_type", nullable = false, length = 20)
    private CourseType courseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", nullable = false, length = 20)
    private MembershipType membershipType;

    @Column(columnDefinition = "TINYINT UNSIGNED")
    private Integer holes;

    @Column(nullable = false)
    private boolean active;

    // 가격 비정규화 컬럼 (#100): 정렬/랭킹/요약 등에서 price_history 전체 JOIN 없이 사용.
    // 소스 무관 "가장 최근에 수집된" 가격 기준 — PriceHistoryRepository#findLatestByCourseIds와 동일 기준
    @Column(name = "latest_price")
    private Long latestPrice;

    @Column(name = "latest_price_source", length = 50)
    private String latestPriceSource;

    @Column(name = "latest_price_at")
    private LocalDateTime latestPriceAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public MembershipCourse(String name, String region, CourseType courseType,
                            MembershipType membershipType, Integer holes) {
        this.name           = name;
        this.region         = region;
        this.courseType     = courseType;
        this.membershipType = membershipType;
        this.holes          = holes;
        this.active         = true;
        this.createdAt      = LocalDateTime.now();
    }

    public void updateHoles(Integer holes) {
        this.holes = holes;
    }

    public void updateRegion(String region) {
        this.region = region;
    }

    // 소스 무관 "가장 최근 수집" 기준으로만 갱신 — 더 과거 collectedAt이면 무시 (여러 소스가 뒤섞여 들어와도 안전)
    public void updateLatestPrice(Long price, String source, LocalDateTime collectedAt) {
        if (this.latestPriceAt != null && !collectedAt.isAfter(this.latestPriceAt)) {
            return;
        }
        this.latestPrice       = price;
        this.latestPriceSource = source;
        this.latestPriceAt     = collectedAt;
    }
}
