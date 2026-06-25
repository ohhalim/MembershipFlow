package com.membershipflow.price.entity;

import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.course.entity.MembershipCourse;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "price_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private MembershipCourse course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private CrawlSource source;

    // 원 단위 (만원×10000)
    @Column(nullable = false)
    private long price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "collect_run_id")
    private CollectRun collectRun;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Builder
    public PriceHistory(MembershipCourse course, CrawlSource source,
                        long price, CollectRun collectRun, LocalDateTime collectedAt) {
        this.course      = course;
        this.source      = source;
        this.price       = price;
        this.collectRun  = collectRun;
        this.collectedAt = collectedAt != null ? collectedAt : LocalDateTime.now();
    }
}
