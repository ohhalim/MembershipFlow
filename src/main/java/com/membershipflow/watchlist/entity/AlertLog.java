package com.membershipflow.watchlist.entity;

import com.membershipflow.collect.entity.CrawlSource;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "alert_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "watchlist_id", nullable = false)
    private Watchlist watchlist;

    @Column(name = "triggered_price", nullable = false)
    private Long triggeredPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private CrawlSource source;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Builder
    public AlertLog(Watchlist watchlist, Long triggeredPrice, CrawlSource source) {
        this.watchlist      = watchlist;
        this.triggeredPrice = triggeredPrice;
        this.source         = source;
        this.sentAt         = LocalDateTime.now();
    }

    public void markRead() {
        this.readAt = LocalDateTime.now();
    }
}
