package com.membershipflow.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CourseNameNormalizer;
import com.membershipflow.collect.collector.DongaHistoryCollector;
import com.membershipflow.collect.collector.DongaInfoCollector;
import com.membershipflow.collect.collector.RegionExtractor;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CourseAlias;
import com.membershipflow.collect.entity.CourseSourceMapping;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CourseAliasRepository;
import com.membershipflow.collect.repository.CourseSourceMappingRepository;
import com.membershipflow.course.entity.CourseInfo;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.CourseInfoRepository;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectPersistenceService {

    private static final String PARSER_VERSION = "1.0";

    private final CourseAliasRepository courseAliasRepository;
    private final CollectRunRepository collectRunRepository;
    private final MembershipCourseRepository membershipCourseRepository;
    private final CourseInfoRepository courseInfoRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final CourseSourceMappingRepository courseSourceMappingRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public CollectRun startRun(CrawlSource source) {
        return collectRunRepository.save(
                CollectRun.builder()
                        .source(source)
                        .parserVersion(PARSER_VERSION)
                        .build());
    }

    @Transactional
    public void failRun(CollectRun run, String errorMessage) {
        run.fail(errorMessage);
        collectRunRepository.save(run);
    }

    @Transactional
    public CollectRun saveCollectedPrices(CrawlSource source, CollectRun run,
                                           List<CollectedPrice> prices) {
        int successCount = 0;
        int failCount = 0;
        List<PriceHistory> toSave = new ArrayList<>();
        Map<String, CourseAlias> aliases = loadAliases();

        for (CollectedPrice cp : prices) {
            try {
                MembershipCourse course = findOrRegisterCourse(
                        cp.courseName(), cp.region(), cp.courseType(),
                        cp.membershipType(), cp.holes(), aliases);
                PriceHistory priceHistory = PriceHistory.builder()
                        .course(course)
                        .source(source)
                        .price(cp.price())
                        .collectRun(run)
                        .build();
                toSave.add(priceHistory);

                // 관리 상태의 MembershipCourse 변경은 저장 트랜잭션 커밋 시 반영
                course.updateLatestPrice(cp.price(), source.getName(), priceHistory.getCollectedAt());
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
        CollectRun savedRun = collectRunRepository.save(run);

        log.info("[{}] 저장 완료: 성공={}, 실패={}", source.getName(), successCount, failCount);
        return savedRun;
    }

    @Transactional
    public int saveHistory(CrawlSource source,
                           List<DongaHistoryCollector.HistoricalPrice> histories) {
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
     * 동아 상세페이지 골프장 부가정보 저장 (#141).
     * 코스 매칭은 alias → normalizer 정규화 후 기존 코스 조회만 수행 — 신규 등록 없음.
     */
    @Transactional
    public int saveCourseInfo(List<DongaInfoCollector.CollectedCourseInfo> infos) {
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

    private MembershipCourse findOrRegisterCourse(String rawName, String region, CourseType courseType,
                                                  MembershipType collectedType, Integer holes,
                                                  Map<String, CourseAlias> aliases) {
        String trimmed = rawName == null ? "" : rawName.trim();

        String name;
        MembershipType extractedType;
        CourseAlias alias = aliases.get(trimmed);
        if (alias != null) {
            name = alias.getCanonicalName();
            extractedType = alias.getMembershipType();
        } else {
            CourseNameNormalizer.NormalizedCourse normalized = CourseNameNormalizer.normalize(trimmed);
            name = normalized.name();
            extractedType = normalized.type();
        }

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
