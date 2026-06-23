package com.membershipflow.collect.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "collect_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private CrawlSource source;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CollectStatus status;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "parser_version", length = 20)
    private String parserVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public CollectRun(CrawlSource source, String parserVersion) {
        this.source        = source;
        this.parserVersion = parserVersion;
        this.startedAt     = LocalDateTime.now();
        this.status        = CollectStatus.RUNNING;
        this.successCount  = 0;
        this.failCount     = 0;
        this.createdAt     = LocalDateTime.now();
    }

    public void complete(int successCount, int failCount) {
        this.successCount = successCount;
        this.failCount    = failCount;
        this.finishedAt   = LocalDateTime.now();
        this.status       = failCount == 0 ? CollectStatus.SUCCESS
                          : successCount == 0 ? CollectStatus.FAIL
                          : CollectStatus.PARTIAL;
    }

    public void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.finishedAt   = LocalDateTime.now();
        this.status       = CollectStatus.FAIL;
    }
}
