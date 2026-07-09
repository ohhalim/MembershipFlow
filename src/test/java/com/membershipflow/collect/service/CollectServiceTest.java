package com.membershipflow.collect.service;

import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.DongaInfoCollector;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CollectStatus;
import com.membershipflow.collect.entity.CourseAlias;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CourseAliasRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CollectServiceTest {

    @Mock CrawlSourceRepository      crawlSourceRepository;
    @Mock CourseAliasRepository       courseAliasRepository;
    @Mock CollectRunRepository        collectRunRepository;
    @Mock MembershipCourseRepository  membershipCourseRepository;
    @Mock CourseInfoRepository        courseInfoRepository;
    @Mock PriceHistoryRepository      priceHistoryRepository;
    @Mock CollectorRegistry           collectorRegistry;
    @Mock PriceCollector              collector;
    @Mock AlertService                alertService;
    @Mock DongaInfoCollector          dongaInfoCollector;

    @InjectMocks CollectService collectService;

    CrawlSource source;
    CollectRun  run;

    @BeforeEach
    void setUp() {
        source = CrawlSource.builder()
                .name("동부회원권").baseUrl("http://dbm-market.co.kr")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();

        run = CollectRun.builder().source(source).parserVersion("1.0").build();
    }

    @Test
    @DisplayName("수집 성공 시 price_history가 저장되고 collect_run이 SUCCESS로 업데이트된다")
    void collectOne_success_savesHistoryAndCompletesRun() {
        // given
        CollectedPrice cp = new CollectedPrice(
                "레이크사이드CC", "경기", CourseType.GOLF, MembershipType.REGULAR,
                18, 438_000_000L, "동부회원권");

        MembershipCourse course = MembershipCourse.builder()
                .name("레이크사이드").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).holes(18)
                .build();

        given(collector.collect()).willReturn(List.of(cp));
        given(collectRunRepository.save(any())).willReturn(run);
        // 끝의 CC가 제거된 정규명으로 조회된다
        given(membershipCourseRepository.findByNameAndCourseTypeAndMembershipType(
                "레이크사이드", CourseType.GOLF, MembershipType.REGULAR))
                .willReturn(Optional.of(course));

        // when
        collectService.collectOne(source, collector);

        // then
        ArgumentCaptor<List<PriceHistory>> captor = ArgumentCaptor.captor();
        then(priceHistoryRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getPrice()).isEqualTo(438_000_000L);
    }

    @Test
    @DisplayName("신규 종목이면 membership_course에 자동 등록된다")
    void collectOne_newCourse_registersAutomatically() {
        // given
        CollectedPrice cp = new CollectedPrice(
                "신규골프장", "강원", CourseType.GOLF, MembershipType.REGULAR,
                18, 100_000_000L, "동부회원권");

        MembershipCourse newCourse = MembershipCourse.builder()
                .name("신규골프장").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).holes(18)
                .build();

        given(collector.collect()).willReturn(List.of(cp));
        given(collectRunRepository.save(any())).willReturn(run);
        given(membershipCourseRepository.findByNameAndCourseTypeAndMembershipType(
                "신규골프장", CourseType.GOLF, MembershipType.REGULAR))
                .willReturn(Optional.empty());
        given(membershipCourseRepository.save(any())).willReturn(newCourse);

        // when
        collectService.collectOne(source, collector);

        // then
        then(membershipCourseRepository).should().save(any(MembershipCourse.class));
    }

    @Test
    @DisplayName("별칭(course_alias)에 등록된 원본명은 정식명·구분으로 치환되어 조회된다")
    void collectOne_aliasedName_resolvesToCanonical() {
        // given — 동아 원본 "88(팔팔)" → 정식명 88, REGULAR
        CollectedPrice cp = new CollectedPrice(
                "88(팔팔)", null, CourseType.GOLF, null,
                null, 438_000_000L, "동아골프");

        MembershipCourse course = MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();

        given(courseAliasRepository.findAll()).willReturn(List.of(
                CourseAlias.builder()
                        .aliasName("88(팔팔)").canonicalName("88")
                        .membershipType(MembershipType.REGULAR)
                        .build()));
        given(collector.collect()).willReturn(List.of(cp));
        given(collectRunRepository.save(any())).willReturn(run);
        given(membershipCourseRepository.findByNameAndCourseTypeAndMembershipType(
                "88", CourseType.GOLF, MembershipType.REGULAR))
                .willReturn(Optional.of(course));

        // when
        collectService.collectOne(source, collector);

        // then — 새 코스를 만들지 않고 기존 정식 코스로 저장
        then(membershipCourseRepository).should(never()).save(any(MembershipCourse.class));
        ArgumentCaptor<List<PriceHistory>> captor = ArgumentCaptor.captor();
        then(priceHistoryRepository).should().saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getCourse().getName()).isEqualTo("88");
    }

    @Test
    @DisplayName("구분이 코스명 끝에 붙은 동아 원본명은 정규화되어 조회된다")
    void collectOne_trailingTypeToken_isNormalized() {
        // given — "경주신라주주" → (경주신라, SHAREHOLDER)
        CollectedPrice cp = new CollectedPrice(
                "경주신라주주", null, CourseType.GOLF, null,
                null, 100_000_000L, "동아골프");

        MembershipCourse course = MembershipCourse.builder()
                .name("경주신라").courseType(CourseType.GOLF)
                .membershipType(MembershipType.SHAREHOLDER)
                .build();

        given(collector.collect()).willReturn(List.of(cp));
        given(collectRunRepository.save(any())).willReturn(run);
        given(membershipCourseRepository.findByNameAndCourseTypeAndMembershipType(
                "경주신라", CourseType.GOLF, MembershipType.SHAREHOLDER))
                .willReturn(Optional.of(course));

        // when
        collectService.collectOne(source, collector);

        // then
        then(membershipCourseRepository).should(never()).save(any(MembershipCourse.class));
    }

    @Test
    @DisplayName("수집기가 CollectException을 던지면 collect_run이 FAIL로 저장되고 price_history는 저장하지 않는다")
    void collectOne_collectorThrows_savesFailRun() {
        // given
        given(collector.collect()).willThrow(new CollectException("연결 실패"));
        given(collectRunRepository.save(any())).willReturn(run);

        // when
        collectService.collectOne(source, collector);

        // then
        then(priceHistoryRepository).should(never()).saveAll(any());

        ArgumentCaptor<CollectRun> runCaptor = ArgumentCaptor.captor();
        then(collectRunRepository).should(org.mockito.Mockito.times(2)).save(runCaptor.capture());
        CollectRun savedRun = runCaptor.getValue();
        assertThat(savedRun.getStatus()).isEqualTo(CollectStatus.FAIL);
    }

    private DongaInfoCollector.CollectedCourseInfo sampleInfo(String courseName, String address) {
        return new DongaInfoCollector.CollectedCourseInfo(
                courseName, address,
                "회원권 소개", "코스 소개", "시세 흐름 및 향후 전망",
                List.of(new DongaInfoCollector.GreenFee("정회원", 68_000L, 73_000L)),
                "1캐디 4백 - 150,000 (1팀당)", "100,000(1대당)");
    }

    @Test
    @DisplayName("부가정보 수집 시 정규명이 일치하는 모든 코스에 CourseInfo가 upsert되고 region이 채워진다")
    void collectCourseInfo_upsertsAllMatchedCoursesAndFillsRegion() {
        // given — 가야일반 링크 하나가 가야(REGULAR/PREFERRED) 두 코스에 매칭
        MembershipCourse regular = MembershipCourse.builder()
                .name("가야").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).build();
        MembershipCourse preferred = MembershipCourse.builder()
                .name("가야").courseType(CourseType.GOLF)
                .membershipType(MembershipType.PREFERRED).build();

        given(dongaInfoCollector.collectAll())
                .willReturn(List.of(sampleInfo("가야일반", "경상남도 김해시 삼안로 148")));
        given(membershipCourseRepository.findAllByNameAndCourseType("가야", CourseType.GOLF))
                .willReturn(List.of(regular, preferred));
        given(courseInfoRepository.findByCourseId(any())).willReturn(Optional.empty());

        // when
        int upserted = collectService.collectCourseInfo();

        // then
        assertThat(upserted).isEqualTo(2);
        assertThat(regular.getRegion()).isEqualTo("경남");
        assertThat(preferred.getRegion()).isEqualTo("경남");

        ArgumentCaptor<CourseInfo> captor = ArgumentCaptor.captor();
        then(courseInfoRepository).should(org.mockito.Mockito.times(2)).save(captor.capture());
        CourseInfo saved = captor.getValue();
        assertThat(saved.getAddress()).isEqualTo("경상남도 김해시 삼안로 148");
        assertThat(saved.getGreenFees()).contains("\"grade\":\"정회원\"").contains("68000").contains("73000");
        assertThat(saved.getCaddieFee()).isEqualTo("1캐디 4백 - 150,000 (1팀당)");
    }

    @Test
    @DisplayName("기존 region이 있는 코스는 덮어쓰지 않는다")
    void collectCourseInfo_existingRegion_isPreserved() {
        // given — 이미 region이 "서울"인 코스
        MembershipCourse course = MembershipCourse.builder()
                .name("가야").region("서울").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).build();

        given(dongaInfoCollector.collectAll())
                .willReturn(List.of(sampleInfo("가야일반", "경상남도 김해시 삼안로 148")));
        given(membershipCourseRepository.findAllByNameAndCourseType("가야", CourseType.GOLF))
                .willReturn(List.of(course));
        given(courseInfoRepository.findByCourseId(any())).willReturn(Optional.empty());

        // when
        collectService.collectCourseInfo();

        // then
        assertThat(course.getRegion()).isEqualTo("서울");
    }

    @Test
    @DisplayName("이미 CourseInfo가 있으면 새로 만들지 않고 갱신한다")
    void collectCourseInfo_existingInfo_isUpdatedNotDuplicated() {
        // given
        MembershipCourse course = MembershipCourse.builder()
                .name("가야").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).build();
        CourseInfo existing = CourseInfo.builder()
                .courseId(course.getId()).address("옛 주소").build();

        given(dongaInfoCollector.collectAll())
                .willReturn(List.of(sampleInfo("가야일반", "경상남도 김해시 삼안로 148")));
        given(membershipCourseRepository.findAllByNameAndCourseType("가야", CourseType.GOLF))
                .willReturn(List.of(course));
        given(courseInfoRepository.findByCourseId(any())).willReturn(Optional.of(existing));

        // when
        int upserted = collectService.collectCourseInfo();

        // then — save 없이 기존 엔티티 필드만 갱신 (dirty checking)
        assertThat(upserted).isEqualTo(1);
        then(courseInfoRepository).should(never()).save(any());
        assertThat(existing.getAddress()).isEqualTo("경상남도 김해시 삼안로 148");
        assertThat(existing.getMembershipIntro()).isEqualTo("회원권 소개");
    }

    @Test
    @DisplayName("매칭되는 코스가 없으면 신규 코스를 등록하지 않고 건너뛴다")
    void collectCourseInfo_noMatchedCourse_registersNothing() {
        // given
        given(dongaInfoCollector.collectAll())
                .willReturn(List.of(sampleInfo("미등록골프장", "경기도 광주시 어딘가 1")));
        given(membershipCourseRepository.findAllByNameAndCourseType("미등록골프장", CourseType.GOLF))
                .willReturn(List.of());

        // when
        int upserted = collectService.collectCourseInfo();

        // then — 정보 수집이 코스를 만들면 안 됨
        assertThat(upserted).isZero();
        then(membershipCourseRepository).should(never()).save(any(MembershipCourse.class));
        then(courseInfoRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("부가정보 코스 매칭도 alias 경로로 정규화된다")
    void collectCourseInfo_aliasedName_resolvesToCanonical() {
        // given — "88(팔팔)" → alias → "88"
        MembershipCourse course = MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).build();

        given(courseAliasRepository.findAll()).willReturn(List.of(
                CourseAlias.builder()
                        .aliasName("88(팔팔)").canonicalName("88")
                        .membershipType(MembershipType.REGULAR)
                        .build()));
        given(dongaInfoCollector.collectAll())
                .willReturn(List.of(sampleInfo("88(팔팔)", "경기도 용인시 기흥구 석성로521번길 169")));
        given(membershipCourseRepository.findAllByNameAndCourseType("88", CourseType.GOLF))
                .willReturn(List.of(course));
        given(courseInfoRepository.findByCourseId(any())).willReturn(Optional.empty());

        // when
        int upserted = collectService.collectCourseInfo();

        // then
        assertThat(upserted).isEqualTo(1);
        assertThat(course.getRegion()).isEqualTo("경기");
        then(courseInfoRepository).should().save(any(CourseInfo.class));
    }

    @Test
    @DisplayName("collectAll은 active=true 소스만 수집한다")
    void collectAll_onlyActiveSourcesAreCollected() {
        // given
        given(crawlSourceRepository.findAllByActiveTrue()).willReturn(List.of(source));
        given(collectorRegistry.find("동부회원권")).willReturn(Optional.of(collector));
        given(collector.collect()).willReturn(List.of());
        given(collectRunRepository.save(any())).willReturn(run);

        // when
        collectService.collectAll();

        // then
        then(collector).should().collect();
    }
}
