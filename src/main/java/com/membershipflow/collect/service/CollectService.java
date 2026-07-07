package com.membershipflow.collect.service;

import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.CourseNameNormalizer;
import com.membershipflow.collect.collector.DongaHistoryCollector;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CourseAlias;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CourseAliasRepository;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import com.membershipflow.watchlist.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectService {

    private static final String PARSER_VERSION = "1.0";

    private final CrawlSourceRepository       crawlSourceRepository;
    private final CourseAliasRepository       courseAliasRepository;
    private final CollectRunRepository        collectRunRepository;
    private final MembershipCourseRepository  membershipCourseRepository;
    private final PriceHistoryRepository      priceHistoryRepository;
    private final CollectorRegistry           collectorRegistry;
    private final AlertService                alertService;
    private final DongaHistoryCollector       dongaHistoryCollector;

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
        Map<String, CourseAlias> aliases = loadAliases();

        for (CollectedPrice cp : prices) {
            try {
                MembershipCourse course = findOrRegisterCourse(
                        cp.courseName(), cp.region(), cp.courseType(),
                        cp.membershipType(), cp.holes(), aliases);
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

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    alertService.checkAndNotify();
                }
            });
        }
    }

    @Transactional
    public int collectHistory() {
        CrawlSource source = crawlSourceRepository.findByName("동아골프")
                .orElseThrow(() -> new IllegalStateException("동아골프 소스 없음"));

        List<DongaHistoryCollector.HistoricalPrice> histories = dongaHistoryCollector.collectAll();

        List<PriceHistory> toSave = new ArrayList<>();
        Map<String, CourseAlias> aliases = loadAliases();
        for (DongaHistoryCollector.HistoricalPrice hp : histories) {
            try {
                MembershipCourse course = findOrRegisterCourse(
                        hp.courseName(), null, hp.courseType(),
                        hp.membershipType(), null, aliases);

                boolean exists = priceHistoryRepository
                        .existsByCourseAndSourceAndCollectedAt(course, source, hp.collectedAt());
                if (!exists) {
                    toSave.add(PriceHistory.builder()
                            .course(course)
                            .source(source)
                            .price(hp.price())
                            .collectedAt(hp.collectedAt())
                            .build());
                }
            } catch (Exception e) {
                log.warn("[동아히스토리] 저장 실패: {} - {}", hp.courseName(), e.getMessage());
            }
        }

        priceHistoryRepository.saveAll(toSave);
        log.info("[동아히스토리] 저장 완료: {}건", toSave.size());
        return toSave.size();
    }

    // 원본 코스명을 alias → CourseNameNormalizer 순으로 정규화한 뒤
    // (정규명, courseType, 최종 membershipType)으로 조회, 없으면 자동 등록
    // INSERT IGNORE 대신 findOrCreate 패턴 사용 (단일 인스턴스 MVP 전제)
    private MembershipCourse findOrRegisterCourse(String rawName, String region, CourseType courseType,
                                                  MembershipType collectedType, Integer holes,
                                                  Map<String, CourseAlias> aliases) {
        String trimmed = rawName == null ? "" : rawName.trim();

        String name;
        MembershipType extractedType;
        CourseAlias alias = aliases.get(trimmed);
        if (alias != null) {
            name          = alias.getCanonicalName();
            extractedType = alias.getMembershipType();
        } else {
            CourseNameNormalizer.NormalizedCourse normalized = CourseNameNormalizer.normalize(trimmed);
            name          = normalized.name();
            extractedType = normalized.type();
        }

        // 수집기가 판별한 구분 우선, 없으면 이름에서 추출한 값, 둘 다 없으면 REGULAR
        MembershipType membershipType = collectedType != null ? collectedType
                : extractedType != null ? extractedType
                : MembershipType.REGULAR;

        return membershipCourseRepository
                .findByNameAndCourseTypeAndMembershipType(name, courseType, membershipType)
                .orElseGet(() -> {
                    log.debug("신규 종목 등록: {} (원본: {})", name, trimmed);
                    return membershipCourseRepository.save(
                            MembershipCourse.builder()
                                    .name(name)
                                    .region(region)
                                    .courseType(courseType)
                                    .membershipType(membershipType)
                                    .holes(holes)
                                    .build());
                });
    }

    private Map<String, CourseAlias> loadAliases() {
        return courseAliasRepository.findAll().stream()
                .collect(Collectors.toMap(CourseAlias::getAliasName, Function.identity()));
    }
}
