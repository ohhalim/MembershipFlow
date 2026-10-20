package com.membershipflow.attendance.entity;

import com.membershipflow.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "attendance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attendance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "attend_date", nullable = false)
    private LocalDate attendDate;

    @Column(name = "day_no", nullable = false)
    private int dayNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Attendance(Member member, LocalDate attendDate, int dayNo) {
        this.member = member;
        this.attendDate = attendDate;
        this.dayNo = dayNo;
        this.createdAt = LocalDateTime.now();
    }
}
