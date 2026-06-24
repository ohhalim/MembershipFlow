package com.membershipflow.watchlist.entity;

import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_watchlist_member_course",
                columnNames = {"member_id", "course_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private MembershipCourse course;

    @Column(name = "target_price")
    private Long targetPrice;

    @Column(name = "alert_yn", nullable = false)
    private boolean alertYn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Watchlist(Member member, MembershipCourse course, Long targetPrice, boolean alertYn) {
        this.member      = member;
        this.course      = course;
        this.targetPrice = targetPrice;
        this.alertYn     = alertYn;
        this.createdAt   = LocalDateTime.now();
        this.updatedAt   = LocalDateTime.now();
    }

    public void update(Long targetPrice, boolean alertYn) {
        this.targetPrice = targetPrice;
        this.alertYn     = alertYn;
        this.updatedAt   = LocalDateTime.now();
    }
}
