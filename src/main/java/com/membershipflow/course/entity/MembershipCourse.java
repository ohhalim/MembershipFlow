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
}
