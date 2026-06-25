package com.membershipflow.collect.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "crawl_source")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CrawlSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "crawl_type", nullable = false, length = 20)
    private CrawlType crawlType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public CrawlSource(String name, String baseUrl, CrawlType crawlType, boolean active) {
        this.name      = name;
        this.baseUrl   = baseUrl;
        this.crawlType = crawlType;
        this.active    = active;
        this.createdAt = LocalDateTime.now();
    }
}
