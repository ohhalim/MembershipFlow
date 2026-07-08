package com.membershipflow.collect.entity;

import com.membershipflow.course.entity.MembershipType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 크롤링 원본 코스명 → 정식 코스명 매핑.
 * 정규화 규칙(CourseNameNormalizer)만으로 처리할 수 없는 별칭
 * ("88(팔팔)"→88, "안성베네스트(구.나다)"→안성베네스트 등)을 관리한다.
 */
@Entity
@Table(
    name = "course_alias",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_course_alias_name",
        columnNames = "alias_name"
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alias_name", nullable = false, length = 200)
    private String aliasName;

    @Column(name = "canonical_name", nullable = false, length = 200)
    private String canonicalName;

    // 별칭 자체가 구분을 내포하면 지정 ("레이크우드일반(구.로얄)"→REGULAR), 아니면 null
    @Enumerated(EnumType.STRING)
    @Column(name = "membership_type", length = 20)
    private MembershipType membershipType;

    @Builder
    public CourseAlias(String aliasName, String canonicalName, MembershipType membershipType) {
        this.aliasName      = aliasName;
        this.canonicalName  = canonicalName;
        this.membershipType = membershipType;
    }
}
