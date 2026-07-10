package com.membershipflow.collect.service;

import com.membershipflow.collect.dto.AdminAlertResponse;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.price.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock CollectRunRepository        collectRunRepository;
    @Mock MembershipCourseRepository  membershipCourseRepository;
    @Mock PriceService                priceService;
    @Mock MemberRepository            memberRepository;
    @Mock SimpMessagingTemplate       messagingTemplate;

    @InjectMocks AnomalyDetectionService anomalyDetectionService;

    private CrawlSource source;
    private Member admin;

    @BeforeEach
    void setUp() {
        source = CrawlSource.builder()
                .name("시세닷컴").baseUrl("http://example.com")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();

        admin = Member.builder().email("admin@test.com").role(MemberRole.ADMIN).build();
        ReflectionTestUtils.setField(admin, "id", 99L);
    }

    private MembershipCourse buildCourse(Long id, String name) {
        MembershipCourse c = MembershipCourse.builder()
                .name(name).region("경기").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private CollectRun completedRun(int successCount) {
        CollectRun run = CollectRun.builder().source(source).parserVersion("1.0").build();
        run.complete(successCount, 0);
        return run;
    }

    // ---------- 1) 수집량 급감 ----------

    @Test
    @DisplayName("직전 대비 successCount가 50% 미만으로 급감하면 관리자에게 COLLECT_DROP 알림을 보낸다")
    void checkCollectDrop_bigDrop_notifiesAdmins() {
        // given — 이전 100건 → 이번 30건
        CollectRun previousRun = completedRun(100);
        CollectRun currentRun  = completedRun(30);

        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.of(previousRun));
        given(memberRepository.findAllByRole(MemberRole.ADMIN)).willReturn(List.of(admin));

        // when
        anomalyDetectionService.checkCollectDrop(source, currentRun);

        // then
        ArgumentCaptor<AdminAlertResponse> captor = ArgumentCaptor.captor();
        then(messagingTemplate).should()
                .convertAndSendToUser(eq("99"), eq("/queue/admin-alert"), captor.capture());
        AdminAlertResponse alert = captor.getValue();
        assertThat(alert.type()).isEqualTo("COLLECT_DROP");
        assertThat(alert.detail()).contains("100").contains("30");
    }

    @Test
    @DisplayName("정상 범위(변동 없음)면 알림을 보내지 않는다")
    void checkCollectDrop_noChange_doesNotNotify() {
        // given — 이전 100건 → 이번 100건
        CollectRun previousRun = completedRun(100);
        CollectRun currentRun  = completedRun(100);

        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.of(previousRun));

        // when
        anomalyDetectionService.checkCollectDrop(source, currentRun);

        // then
        then(messagingTemplate).shouldHaveNoInteractions();
        then(memberRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정확히 50%로 떨어진 경계값은 급감으로 보지 않는다")
    void checkCollectDrop_exactlyHalf_isNotAnomaly() {
        // given — 이전 100건 → 이번 50건 (정확히 절반)
        CollectRun previousRun = completedRun(100);
        CollectRun currentRun  = completedRun(50);

        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.of(previousRun));

        // when
        anomalyDetectionService.checkCollectDrop(source, currentRun);

        // then
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("직전 run이 없으면(최초 수집) 비교 없이 조용히 넘어간다")
    void checkCollectDrop_noPreviousRun_doesNothing() {
        // given
        CollectRun currentRun = completedRun(30);
        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.empty());

        // when
        anomalyDetectionService.checkCollectDrop(source, currentRun);

        // then
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    // ---------- 2) 거래소간 가격 이상치 ----------

    @Test
    @DisplayName("화산 케이스 재현 — 시세닷컴만 median 대비 큰 폭으로 이탈해 이상치로 잡힌다")
    void checkPriceOutliers_hwasanCase_detectsSisePricomAsOutlier() {
        // given — 시세닷컴 1.2억 / 동부 11.6억 / 동아 11.8억 / 에이스 12억
        // 정렬: 1.2억, 11.6억, 11.8억, 12억 → median = (11.6억+11.8억)/2 = 11.7억
        // 시세닷컴 편차 = (1.2억-11.7억)/11.7억 ≈ -89.7% → 이상치
        // 나머지 3개는 median 대비 3% 이내로 정상
        MembershipCourse hwasan = buildCourse(10L, "화산");
        given(membershipCourseRepository.findAllByActiveTrue()).willReturn(List.of(hwasan));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{10L, "시세닷컴", 120_000_000L},
                new Object[]{10L, "동부회원권", 1_160_000_000L},
                new Object[]{10L, "동아골프", 1_180_000_000L},
                new Object[]{10L, "에이스회원권", 1_200_000_000L}));
        given(memberRepository.findAllByRole(MemberRole.ADMIN)).willReturn(List.of(admin));

        // when
        anomalyDetectionService.checkPriceOutliers();

        // then — 오직 시세닷컴 1건만 이상치 알림
        ArgumentCaptor<AdminAlertResponse> captor = ArgumentCaptor.captor();
        then(messagingTemplate).should()
                .convertAndSendToUser(eq("99"), eq("/queue/admin-alert"), captor.capture());
        AdminAlertResponse alert = captor.getValue();
        assertThat(alert.type()).isEqualTo("PRICE_OUTLIER");
        assertThat(alert.courseId()).isEqualTo(10L);
        assertThat(alert.courseName()).isEqualTo("화산");
        assertThat(alert.detail()).contains("시세닷컴").contains("1170000000");
    }

    @Test
    @DisplayName("모든 소스가 median ±50% 이내면 이상치가 없다")
    void checkPriceOutliers_withinRange_noAnomaly() {
        // given — 동아 450M / 동부 438M / 에이스 460M (median 450M, 모두 3% 이내)
        MembershipCourse course = buildCourse(11L, "정상장");
        given(membershipCourseRepository.findAllByActiveTrue()).willReturn(List.of(course));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{11L, "동아골프", 450_000_000L},
                new Object[]{11L, "동부회원권", 438_000_000L},
                new Object[]{11L, "에이스회원권", 460_000_000L}));

        // when
        anomalyDetectionService.checkPriceOutliers();

        // then
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("소스가 2개뿐인 코스는 어느 쪽이 이상인지 판단할 수 없으므로 median 비교 대상에서 제외한다")
    void checkPriceOutliers_onlyTwoSources_isExcluded() {
        // given — 2개 소스는 10배 차이가 나도 검사 대상이 아니다
        MembershipCourse course = buildCourse(12L, "듀오장");
        given(membershipCourseRepository.findAllByActiveTrue()).willReturn(List.of(course));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{12L, "동아골프", 100_000_000L},
                new Object[]{12L, "시세닷컴", 900_000_000L}));

        // when
        anomalyDetectionService.checkPriceOutliers();

        // then
        then(messagingTemplate).shouldHaveNoInteractions();
        then(memberRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("짝수 개 소스는 가운데 두 값의 평균을 median으로 계산한다")
    void checkPriceOutliers_evenCount_usesAverageOfTwoMiddleValues() {
        // given — 정렬: 100M,110M,120M,1000M → median=(110M+120M)/2=115M
        // D(1000M)만 +769% 이탈로 이상치, 나머지 3개는 ±13% 이내로 정상
        MembershipCourse course = buildCourse(13L, "짝수장");
        given(membershipCourseRepository.findAllByActiveTrue()).willReturn(List.of(course));
        given(priceService.getLatestPerSourceRows(anyList())).willReturn(List.of(
                new Object[]{13L, "A", 100_000_000L},
                new Object[]{13L, "B", 110_000_000L},
                new Object[]{13L, "C", 120_000_000L},
                new Object[]{13L, "D", 1_000_000_000L}));
        given(memberRepository.findAllByRole(MemberRole.ADMIN)).willReturn(List.of(admin));

        // when
        anomalyDetectionService.checkPriceOutliers();

        // then
        ArgumentCaptor<AdminAlertResponse> captor = ArgumentCaptor.captor();
        then(messagingTemplate).should()
                .convertAndSendToUser(eq("99"), eq("/queue/admin-alert"), captor.capture());
        AdminAlertResponse alert = captor.getValue();
        assertThat(alert.detail()).contains("115000000").contains("D");
    }

    @Test
    @DisplayName("활성 코스가 없으면 아무 것도 조회하지 않고 조용히 넘어간다")
    void checkPriceOutliers_noActiveCourses_doesNothing() {
        given(membershipCourseRepository.findAllByActiveTrue()).willReturn(List.of());

        anomalyDetectionService.checkPriceOutliers();

        then(priceService).shouldHaveNoInteractions();
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    // ---------- 3) 관리자 알림 격리 ----------

    @Test
    @DisplayName("관리자가 없으면 예외 없이 조용히 넘어간다")
    void notifyAdmins_noAdmins_doesNotThrowAndSkipsNotification() {
        // given
        CollectRun previousRun = completedRun(100);
        CollectRun currentRun  = completedRun(10);

        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.of(previousRun));
        given(memberRepository.findAllByRole(MemberRole.ADMIN)).willReturn(List.of());

        // when / then
        assertThatCode(() -> anomalyDetectionService.checkCollectDrop(source, currentRun))
                .doesNotThrowAnyException();
        then(messagingTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("알림 발송이 예외를 던져도 이상 탐지(수집) 자체는 실패하지 않는다")
    void notifyAdmins_sendThrows_isolatesExceptionFromCaller() {
        // given
        CollectRun previousRun = completedRun(100);
        CollectRun currentRun  = completedRun(10);

        given(collectRunRepository.findTopBySourceAndIdLessThanOrderByIdDesc(eq(source), any()))
                .willReturn(Optional.of(previousRun));
        given(memberRepository.findAllByRole(MemberRole.ADMIN)).willReturn(List.of(admin));
        doThrow(new RuntimeException("웹소켓 세션 없음"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());

        // when / then — 알림 실패가 호출자에게 전파되지 않는다
        assertThatCode(() -> anomalyDetectionService.checkCollectDrop(source, currentRun))
                .doesNotThrowAnyException();
    }
}
