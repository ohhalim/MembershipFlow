package com.membershipflow.course.service;

import com.membershipflow.course.dto.CourseDetailResponse;
import com.membershipflow.course.dto.CourseListItemResponse;
import com.membershipflow.course.dto.MarketSummaryResponse;
import com.membershipflow.course.dto.SourceComparisonItem;
import com.membershipflow.course.entity.CourseInfo;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.CourseInfoRepository;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    @Mock MembershipCourseRepository courseRepository;
    @Mock CourseInfoRepository courseInfoRepository;
    @Mock PriceService priceService;

    @InjectMocks CourseService courseService;

    private static final long COURSE_ID = 1L;
    private final Pageable pageable = PageRequest.of(0, 20);

    private MembershipCourse course;
    private PriceHistory latest;

    @BeforeEach
    void setUp() {
        course = MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        // 소스 무관 최신가: 동아 450,000,000 (가장 최근 수집)
        latest = PriceHistory.builder()
                .price(450_000_000L)
                .collectedAt(LocalDateTime.of(2026, 7, 7, 7, 0))
                .build();
    }

    private void stubSearch(List<Object[]> sourceRows) {
        given(courseRepository.search(any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(course), pageable, 1));
        given(priceService.getLatestPriceBatch(anyList()))
                .willReturn(Map.of(COURSE_ID, latest));
        given(priceService.getLatestPerSourceRows(anyList()))
                .willReturn(sourceRows);
    }

    private MembershipCourse buildCourse(Long id, String name, String region) {
        MembershipCourse c = MembershipCourse.builder()
                .name(name).region(region).courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    @Test
    @DisplayName("상세 조회 시 CourseInfo가 있으면 greenFees JSON이 역직렬화되어 info로 매핑된다")
    void getDetail_withCourseInfo_mapsInfoAndDeserializesGreenFees() {
        // given
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
        given(priceService.getLatestBySource(COURSE_ID)).willReturn(List.of());
        given(courseInfoRepository.findByCourseId(COURSE_ID)).willReturn(Optional.of(
                CourseInfo.builder()
                        .courseId(COURSE_ID)
                        .address("경기도 용인시 기흥구 석성로521번길 169")
                        .membershipIntro("회원권 소개")
                        .greenFees("[{\"grade\":\"정회원\",\"weekday\":68000,\"weekend\":73000}]")
                        .caddieFee("1캐디 4백 - 150,000 (1팀당)")
                        .build()));

        // when
        CourseDetailResponse detail = courseService.getDetail(COURSE_ID);

        // then
        assertThat(detail.info()).isNotNull();
        assertThat(detail.info().address()).isEqualTo("경기도 용인시 기흥구 석성로521번길 169");
        assertThat(detail.info().greenFees()).hasSize(1);
        assertThat(detail.info().greenFees().get(0).grade()).isEqualTo("정회원");
        assertThat(detail.info().greenFees().get(0).weekday()).isEqualTo(68_000L);
        assertThat(detail.info().greenFees().get(0).weekend()).isEqualTo(73_000L);
    }

    @Test
    @DisplayName("상세 조회 시 CourseInfo가 없으면 info=null이다")
    void getDetail_withoutCourseInfo_returnsNullInfo() {
        // given
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
        given(priceService.getLatestBySource(COURSE_ID)).willReturn(List.of());
        given(courseInfoRepository.findByCourseId(COURSE_ID)).willReturn(Optional.empty());

        // when
        CourseDetailResponse detail = courseService.getDetail(COURSE_ID);

        // then
        assertThat(detail.info()).isNull();
    }

    @Test
    @DisplayName("대표 가격은 거래소별 최신가 중 최저가다 (매수자 관점)")
    void search_listPrice_isMinOfSourcePrices() {
        // given — 동아 450,000,000 / 동부 438,000,000
        stubSearch(List.of(
                new Object[]{COURSE_ID, "동아골프", 450_000_000L},
                new Object[]{COURSE_ID, "동부회원권", 438_000_000L}));

        // when
        Page<CourseListItemResponse> result =
                courseService.search(null, null, null, null, null, pageable);

        // then — 최신 수집가(450M)가 아니라 거래소 최저가(438M)가 대표 가격
        CourseListItemResponse item = result.getContent().get(0);
        assertThat(item.latestPrice()).isEqualTo(438_000_000L);
        assertThat(item.sourcePrices()).hasSize(2);
        // updatedAt은 기존 로직(소스 무관 최신 수집 시각) 유지
        assertThat(item.updatedAt()).isEqualTo("2026-07-07T07:00");
    }

    @Test
    @DisplayName("거래소별 가격이 없으면 소스 무관 최신가로 폴백한다")
    void search_noSourcePrices_fallsBackToLatest() {
        // given
        stubSearch(List.of());

        // when
        Page<CourseListItemResponse> result =
                courseService.search(null, null, null, null, null, pageable);

        // then
        CourseListItemResponse item = result.getContent().get(0);
        assertThat(item.latestPrice()).isEqualTo(450_000_000L);
        assertThat(item.sourcePrices()).isEmpty();
    }

    @Test
    @DisplayName("changeRate는 대표 가격이 아닌 기존 로직(소스 무관 최신가 vs 7일 기준가)을 유지한다")
    void search_changeRate_keepsExistingLogic() {
        // given — 7일 전 기준가 400,000,000 → (450M-400M)/400M = +12.5%
        PriceHistory base = PriceHistory.builder()
                .price(400_000_000L)
                .collectedAt(LocalDateTime.of(2026, 6, 30, 7, 0))
                .build();
        stubSearch(List.of(
                new Object[]{COURSE_ID, "동아골프", 450_000_000L},
                new Object[]{COURSE_ID, "동부회원권", 438_000_000L}));
        given(priceService.get7dBasePriceBatch(anyList()))
                .willReturn(Map.of(COURSE_ID, base));

        // when
        Page<CourseListItemResponse> result =
                courseService.search(null, null, null, null, null, pageable);

        // then — 대표 가격은 최저가지만 등락률은 최신 수집가 기준
        CourseListItemResponse item = result.getContent().get(0);
        assertThat(item.latestPrice()).isEqualTo(438_000_000L);
        assertThat(item.changeRate()).isEqualTo(12.5);
    }

    @Test
    @DisplayName("소스가 4개인 코스는 min/max/diffAmount/diffRate가 N-way로 계산된다")
    void getSourceComparison_calculatesMinMaxDiff_forMultiSourceCourse() {
        // given — 88 코스: 동아 450M / 동부 438M / 시세닷컴 430M / 에이스 460M
        MembershipCourse course88 = buildCourse(1L, "88", "경기");
        given(courseRepository.findAll()).willReturn(List.of(course88));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{1L, "동아골프", 450_000_000L},
                new Object[]{1L, "동부회원권", 438_000_000L},
                new Object[]{1L, "시세닷컴", 430_000_000L},
                new Object[]{1L, "에이스회원권", 460_000_000L}));

        // when
        List<SourceComparisonItem> result = courseService.getSourceComparison(10);

        // then — min 430M / max 460M / diff 30M / diffRate 6.98%
        assertThat(result).hasSize(1);
        SourceComparisonItem item = result.get(0);
        assertThat(item.courseId()).isEqualTo(1L);
        assertThat(item.name()).isEqualTo("88");
        assertThat(item.prices()).hasSize(4)
                .extracting(SourceComparisonItem.SourcePricePoint::sourceName)
                .containsExactlyInAnyOrder("동아골프", "동부회원권", "시세닷컴", "에이스회원권");
        assertThat(item.minPrice()).isEqualTo(430_000_000L);
        assertThat(item.maxPrice()).isEqualTo(460_000_000L);
        assertThat(item.diffAmount()).isEqualTo(30_000_000L);
        assertThat(item.diffRate()).isEqualTo(6.98);
    }

    @Test
    @DisplayName("소스가 1개뿐인 코스는 비교 불가하므로 결과에서 제외된다")
    void getSourceComparison_excludesSingleSourceCourse() {
        // given
        MembershipCourse lonely = buildCourse(2L, "외톨이CC", "강원");
        given(courseRepository.findAll()).willReturn(List.of(lonely));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.<Object[]>of(
                new Object[]{2L, "동아골프", 100_000_000L}));

        // when
        List<SourceComparisonItem> result = courseService.getSourceComparison(10);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("비활성 코스는 비교 대상에서 제외된다")
    void getSourceComparison_excludesInactiveCourse() {
        // given
        MembershipCourse inactive = buildCourse(4L, "폐장CC", "제주");
        ReflectionTestUtils.setField(inactive, "active", false);
        given(courseRepository.findAll()).willReturn(List.of(inactive));

        // when
        List<SourceComparisonItem> result = courseService.getSourceComparison(10);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("diffRate 절대값 내림차순으로 정렬하고 limit을 적용한다")
    void getSourceComparison_sortsByDiffRateDesc_andAppliesLimit() {
        // given — 88 코스 diffRate 6.98% (430M~460M), Small 코스 diffRate 5.0% (200M~210M)
        MembershipCourse course88 = buildCourse(1L, "88", "경기");
        MembershipCourse courseSmall = buildCourse(3L, "Small", "서울");
        given(courseRepository.findAll()).willReturn(List.of(course88, courseSmall));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{1L, "동아골프", 450_000_000L},
                new Object[]{1L, "동부회원권", 438_000_000L},
                new Object[]{1L, "시세닷컴", 430_000_000L},
                new Object[]{1L, "에이스회원권", 460_000_000L},
                new Object[]{3L, "동부회원권", 200_000_000L},
                new Object[]{3L, "시세닷컴", 210_000_000L}));

        // when — limit=1이므로 diffRate가 더 큰 88 코스만 반환
        List<SourceComparisonItem> result = courseService.getSourceComparison(1);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).courseId()).isEqualTo(1L);
        assertThat(result.get(0).diffRate()).isEqualTo(6.98);
    }

    @Test
    @DisplayName("getSummary는 소스 2개 이상 종목 수(comparedCourses)와 최대 스프레드율(maxSpreadRate)을 계산한다")
    void getSummary_calculatesSpreadMetrics() {
        // given — 88(4소스 430M~460M → 6.98%), Small(2소스 200M/210M → 5.0%), 외톨이(1소스, 제외)
        MembershipCourse course88 = buildCourse(1L, "88", "경기");
        MembershipCourse courseSmall = buildCourse(3L, "Small", "서울");
        MembershipCourse lonely = buildCourse(2L, "외톨이CC", "강원");
        given(courseRepository.findAll()).willReturn(List.of(course88, courseSmall, lonely));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{1L, "동아골프", 450_000_000L},
                new Object[]{1L, "동부회원권", 438_000_000L},
                new Object[]{1L, "시세닷컴", 430_000_000L},
                new Object[]{1L, "에이스회원권", 460_000_000L},
                new Object[]{3L, "동부회원권", 200_000_000L},
                new Object[]{3L, "시세닷컴", 210_000_000L},
                new Object[]{2L, "동아골프", 100_000_000L}));

        // when
        MarketSummaryResponse summary = courseService.getSummary();

        // then — 소스 1개인 외톨이는 제외, 최대 스프레드는 88 코스의 6.98%
        assertThat(summary.comparedCourses()).isEqualTo(2);
        assertThat(summary.maxSpreadRate()).isEqualTo(6.98);
    }

    @Test
    @DisplayName("getSummary는 소스가 1개뿐인 종목을 comparedCourses에서 제외한다")
    void getSummary_excludesSingleSourceCourse() {
        // given
        MembershipCourse lonely = buildCourse(2L, "외톨이CC", "강원");
        given(courseRepository.findAll()).willReturn(List.of(lonely));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.<Object[]>of(
                new Object[]{2L, "동아골프", 100_000_000L}));

        // when
        MarketSummaryResponse summary = courseService.getSummary();

        // then
        assertThat(summary.comparedCourses()).isEqualTo(0);
        assertThat(summary.maxSpreadRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("getSummary는 활성 종목이 없으면 모든 지표가 0이다")
    void getSummary_noCourses_returnsZeros() {
        // given
        given(courseRepository.findAll()).willReturn(List.of());

        // when
        MarketSummaryResponse summary = courseService.getSummary();

        // then
        assertThat(summary.updatedToday()).isEqualTo(0);
        assertThat(summary.risers()).isEqualTo(0);
        assertThat(summary.fallers()).isEqualTo(0);
        assertThat(summary.comparedCourses()).isEqualTo(0);
        assertThat(summary.maxSpreadRate()).isEqualTo(0.0);
    }
}
