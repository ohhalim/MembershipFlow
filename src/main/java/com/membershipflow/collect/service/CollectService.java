package com.membershipflow.collect.service;

import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.DongaHistoryCollector;
import com.membershipflow.collect.collector.DongaInfoCollector;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.watchlist.service.AlertService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectService {

    private final CrawlSourceRepository crawlSourceRepository;
    private final CollectorRegistry collectorRegistry;
    private final AlertService alertService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final DongaHistoryCollector dongaHistoryCollector;
    private final DongaInfoCollector dongaInfoCollector;
    private final CollectPersistenceService persistenceService;
    private final MeterRegistry meterRegistry;

    // 배치 하트비트 (#188): collectAll()이 끝까지 실행 완료된 시각(epoch seconds).
    // Grafana에서 이 값이 오래되면(스케줄러가 안 돌았거나 도중에 죽었으면) 알림
    private final AtomicLong collectLastRunTimestamp = new AtomicLong(0);

    @PostConstruct
    void registerMetrics() {
        Gauge.builder("collect_last_run_timestamp_seconds", collectLastRunTimestamp, AtomicLong::get)
                .description("마지막으로 collectAll()이 끝까지 실행 완료된 시각(epoch seconds)")
                .register(meterRegistry);
    }

    public void collectAll() {
        List<CrawlSource> sources = crawlSourceRepository.findAllByActiveTrue();
        for (CrawlSource source : sources) {
            collectorRegistry.find(source.getName())
                    .ifPresentOrElse(
                            collector -> collectOne(source, collector),
                            () -> log.warn("수집기 없음: {}", source.getName()));
        }
        // 전체 소스 수집 완료 후 거래소간 가격 이상치 탐지 (#159)
        anomalyDetectionService.checkPriceOutliers();
        collectLastRunTimestamp.set(Instant.now().getEpochSecond());
    }

    public void collectOne(CrawlSource source, PriceCollector collector) {
        CollectRun run = persistenceService.startRun(source);

        List<CollectedPrice> prices;
        try {
            // 외부 사이트 네트워크 I/O — DB 트랜잭션 밖에서 실행
            prices = collector.collect();
        } catch (CollectException e) {
            log.error("[{}] 수집 실패: {}", source.getName(), e.getMessage(), e);
            persistenceService.failRun(run, e.getMessage());
            return;
        }

        CollectRun completedRun;
        try {
            completedRun = persistenceService.saveCollectedPrices(source, run, prices);
        } catch (RuntimeException e) {
            log.error("[{}] 저장 실패: {}", source.getName(), e.getMessage(), e);
            persistenceService.failRun(run, e.getMessage());
            return;
        }

        // saveCollectedPrices 반환 시점에는 저장 트랜잭션 커밋 완료
        alertService.checkAndNotify();
        anomalyDetectionService.checkCollectDrop(source, completedRun);
    }

    public int collectHistory() {
        CrawlSource source = crawlSourceRepository.findByName("동아골프")
                .orElseThrow(() -> new IllegalStateException("동아골프 소스 없음"));

        // 외부 chart API 호출 — DB 트랜잭션 밖에서 실행
        List<DongaHistoryCollector.HistoricalPrice> histories = dongaHistoryCollector.collectAll();
        return persistenceService.saveHistory(source, histories);
    }

    public int collectCourseInfo() {
        // 외부 상세 페이지 수집 — DB 트랜잭션 밖에서 실행
        List<DongaInfoCollector.CollectedCourseInfo> infos = dongaInfoCollector.collectAll();
        return persistenceService.saveCourseInfo(infos);
    }
}
