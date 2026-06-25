package com.membershipflow.collect.entity;

import com.membershipflow.course.entity.MembershipCourse;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "course_source_mapping",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_course_source",
        columnNames = {"course_id", "source_id"}
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseSourceMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private MembershipCourse course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private CrawlSource source;

    // 동부: sidx 값, 동아: "custid:code" 형태
    @Column(name = "source_key", nullable = false, length = 100)
    private String sourceKey;

    @Builder
    public CourseSourceMapping(MembershipCourse course, CrawlSource source, String sourceKey) {
        this.course    = course;
        this.source    = source;
        this.sourceKey = sourceKey;
    }
}
