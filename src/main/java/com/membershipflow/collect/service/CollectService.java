package com.membershipflow.collect.service;

import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectService {

    private static final String PARSER_VERSION = "1.0";

    private final CrawlSourceRepository       crawlSourceRepository;
    private final CollectRunRepository        collectRunRepository;
    private final MembershipCourseRepository  membershipCourseRepository;
    private final PriceHistoryRepository      priceHistoryRepository;
    private final CollectorRegistry           collectorRegistry;

    public void collectAll() {
        List<CrawlSource> sources = crawlSourceRepository.findAllByActiveTrue();
        for (CrawlSource source : sources) {
            collectorRegistry.find(source.getName())
                    .ifPresentOrElse(
                            collector -> collectOne(source, collector),
                            () -> log.warn("수집기 없음: {}", source.getName()));
        }
    }

    @Transactional
    public void collectOne(CrawlSource source, PriceCollector collector) {
        CollectRun run = collectRunRepository.save(
                CollectRun.builder()
                        .source(source)
                        .parserVersion(PARSER_VERSION)
                        .build());

        List<CollectedPrice> prices;
        try {
            prices = collector.collect();
        } catch (CollectException e) {
            log.error("[{}] 수집 실패: {}", source.getName(), e.getMessage(), e);
            run.fail(e.getMessage());
            collectRunRepository.save(run);
            return;
        }

        int successCount = 0;
        int failCount    = 0;
        List<PriceHistory> toSave = new ArrayList<>();

        for (CollectedPrice cp : prices) {
            try {
                MembershipCourse course = findOrRegisterCourse(cp);
                toSave.add(PriceHistory.builder()
                        .course(course)
                        .source(source)
                        .price(cp.price())
                        .collectRun(run)
                        .build());
                successCount++;
            } catch (Exception e) {
                log.warn("[{}] 종목 저장 실패: {} - {}", source.getName(), cp.courseName(), e.getMessage());
                failCount++;
            }
        }

        priceHistoryRepository.saveAll(toSave);
        run.complete(successCount, failCount);
        collectRunRepository.save(run);

        log.info("[{}] 저장 완료: 성공={}, 실패={}", source.getName(), successCount, failCount);
    }

    // 이름+courseType+membershipType 으로 종목 조회, 없으면 자동 등록
    // INSERT IGNORE 대신 findOrCreate 패턴 사용 (단일 인스턴스 MVP 전제)
    private MembershipCourse findOrRegisterCourse(CollectedPrice cp) {
        return membershipCourseRepository
                .findByNameAndCourseTypeAndMembershipType(
                        cp.courseName(), cp.courseType(), cp.membershipType())
                .orElseGet(() -> {
                    log.debug("신규 종목 등록: {}", cp.courseName());
                    return membershipCourseRepository.save(
                            MembershipCourse.builder()
                                    .name(cp.courseName())
                                    .region(cp.region())
                                    .courseType(cp.courseType())
                                    .membershipType(cp.membershipType())
                                    .holes(cp.holes())
                                    .build());
                });
    }
}
