package com.membershipflow.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.CourseNameNormalizer;
import com.membershipflow.collect.collector.DongaHistoryCollector;
import com.membershipflow.collect.collector.DongaInfoCollector;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.collector.RegionExtractor;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CourseAlias;
import com.membershipflow.collect.entity.CourseSourceMapping;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CourseAliasRepository;
import com.membershipflow.collect.repository.CourseSourceMappingRepository;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.course.entity.CourseInfo;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.CourseInfoRepository;
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

    private final CrawlSourceRepository            crawlSourceRepository;
    private final CourseAliasRepository            courseAliasRepository;
    private final CollectRunRepository             collectRunRepository;
    private final MembershipCourseRepository       membershipCourseRepository;
    private final CourseInfoRepository             courseInfoRepository;
    private final PriceHistoryRepository           priceHistoryRepository;
    private final CourseSourceMappingRepository    courseSourceMappingRepository;
    private final CollectorRegistry                collectorRegistry;
    private final AlertService                     alertService;
    private final AnomalyDetectionService          anomalyDetectionService;
    private final DongaHistoryCollector            dongaHistoryCollector;
    private final DongaInfoCollector               dongaInfoCollector;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                if (cp.sourceKey() != null) {
                    upsertSourceMapping(course, source, cp.sourceKey());
                }
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

        // 같은 소스의 직전 수집 대비 성공 건수 급감 탐지 (#159)
        anomalyDetectionService.checkCollectDrop(source, run);

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

    /**
     * 동아 상세페이지 골프장 부가정보 수집 (#141).
     * 코스 매칭은 alias → normalizer 정규화 후 기존 코스 조회만 수행 — 신규 등록 없음.
     * 같은 골프장의 회원권 여러 개(일반/우대/주중 등)가 정보를 공유하므로
     * 정규명이 일치하는 모든 코스에 CourseInfo upsert + region(비어있을 때만) 채움.
     */
    @Transactional
    public int collectCourseInfo() {
        List<DongaInfoCollector.CollectedCourseInfo> infos = dongaInfoCollector.collectAll();

        Map<String, CourseAlias> aliases = loadAliases();
        int upserted = 0;
        int unmatched = 0;

        for (DongaInfoCollector.CollectedCourseInfo info : infos) {
            try {
                String canonicalName = resolveCanonicalName(info.courseName(), aliases);
                List<MembershipCourse> courses = membershipCourseRepository
                        .findAllByNameAndCourseType(canonicalName, CourseType.GOLF);
                if (courses.isEmpty()) {
                    log.debug("[동아부가정보] 매칭 코스 없음: {} (정규명: {})", info.courseName(), canonicalName);
                    unmatched++;
                    continue;
                }

                String region = RegionExtractor.extract(info.address());
                String greenFeesJson = info.greenFees() == null ? null
                        : objectMapper.writeValueAsString(info.greenFees());

                for (MembershipCourse course : courses) {
                    // 기존 region이 있으면 덮어쓰지 않음
                    if (course.getRegion() == null && region != null) {
                        course.updateRegion(region);
                    }
                    upsertCourseInfo(course.getId(), info, greenFeesJson);
                    upserted++;
                }
            } catch (Exception e) {
                log.warn("[동아부가정보] 저장 실패: {} - {}", info.courseName(), e.getMessage());
            }
        }

        log.info("[동아부가정보] 저장 완료: upsert={}건, 미매칭 골프장={}곳", upserted, unmatched);
        return upserted;
    }

    private void upsertCourseInfo(Long courseId, DongaInfoCollector.CollectedCourseInfo info,
                                  String greenFeesJson) {
        courseInfoRepository.findByCourseId(courseId).ifPresentOrElse(
                existing -> existing.update(
                        info.address(), info.membershipIntro(), info.courseIntro(),
                        info.priceOutlook(), greenFeesJson, info.caddieFee(), info.cartFee()),
                () -> courseInfoRepository.save(CourseInfo.builder()
                        .courseId(courseId)
                        .address(info.address())
                        .membershipIntro(info.membershipIntro())
                        .courseIntro(info.courseIntro())
                        .priceOutlook(info.priceOutlook())
                        .greenFees(greenFeesJson)
                        .caddieFee(info.caddieFee())
                        .cartFee(info.cartFee())
                        .build()));
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

    // course_source_mapping upsert (#144). uk_course_source(course_id+source_id) 기준
    private void upsertSourceMapping(MembershipCourse course, CrawlSource source, String sourceKey) {
        courseSourceMappingRepository.findByCourseAndSource(course, source)
                .ifPresentOrElse(
                        existing -> existing.updateSourceKey(sourceKey),
                        () -> courseSourceMappingRepository.save(
                                CourseSourceMapping.builder()
                                        .course(course)
                                        .source(source)
                                        .sourceKey(sourceKey)
                                        .build()));
    }

    // findOrRegisterCourse와 동일한 alias → CourseNameNormalizer 경로로 정규명만 추출
    private String resolveCanonicalName(String rawName, Map<String, CourseAlias> aliases) {
        String trimmed = rawName == null ? "" : rawName.trim();
        CourseAlias alias = aliases.get(trimmed);
        if (alias != null) {
            return alias.getCanonicalName();
        }
        return CourseNameNormalizer.normalize(trimmed).name();
    }

    private Map<String, CourseAlias> loadAliases() {
        return courseAliasRepository.findAll().stream()
                .collect(Collectors.toMap(CourseAlias::getAliasName, Function.identity()));
    }
}
