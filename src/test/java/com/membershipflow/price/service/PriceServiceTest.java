package com.membershipflow.price.service;

import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.dto.LatestSourcePriceResponse;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock MembershipCourseRepository courseRepository;
    @Mock PriceHistoryRepository priceHistoryRepository;
    @Mock SourceUrlResolver sourceUrlResolver;

    @InjectMocks PriceService priceService;

    MembershipCourse course;
    CrawlSource donga;
    CrawlSource dongbu;

    @BeforeEach
    void setUp() {
        course = MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
        donga = CrawlSource.builder()
                .name("동아골프").baseUrl("https://www.dongagolf.co.kr/membership/sise/")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        dongbu = CrawlSource.builder()
                .name("동부회원권").baseUrl("http://dbm-market.co.kr")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
    }

    @Test
    @DisplayName("소스별 최신가의 URL은 SourceUrlResolver가 리졸브한 값으로 채워진다")
    void getLatestBySource_resolvesUrlPerSource() {
        // given
        LocalDateTime now = LocalDateTime.now();
        PriceHistory dongaHistory  = PriceHistory.builder().course(course).source(donga).price(438_000_000L).collectedAt(now).build();
        PriceHistory dongbuHistory = PriceHistory.builder().course(course).source(dongbu).price(435_000_000L).collectedAt(now).build();

        given(courseRepository.findById(1L)).willReturn(Optional.of(course));
        given(priceHistoryRepository.findLatestBySource(1L)).willReturn(List.of(dongaHistory, dongbuHistory));
        given(sourceUrlResolver.resolve(course, donga))
                .willReturn("https://www.dongagolf.co.kr/membership/info?custid=10130&code=1103");
        given(sourceUrlResolver.resolve(course, dongbu))
                .willReturn("http://dbm-market.co.kr");

        // when
        List<LatestSourcePriceResponse> result = priceService.getLatestBySource(1L);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).sourceUrl())
                .isEqualTo("https://www.dongagolf.co.kr/membership/info?custid=10130&code=1103");
        assertThat(result.get(1).sourceUrl()).isEqualTo("http://dbm-market.co.kr");
    }

    @Test
    @DisplayName("존재하지 않는 코스면 BusinessException을 던진다")
    void getLatestBySource_courseNotFound_throwsException() {
        // given
        given(courseRepository.findById(999L)).willReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> priceService.getLatestBySource(999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("비구독자의 장기 차트가 7일로 제한되면 일별로 집계한다")
    void getChart_nonSubscriberLongPeriod_usesDailyInterval() {
        // given
        LocalDate today = LocalDate.now();
        given(courseRepository.findById(1L)).willReturn(Optional.of(course));
        given(priceHistoryRepository.findChartByDay(eq(1L), any(), any()))
                .willReturn(List.of());

        // when
        var result = priceService.getChart(
                1L, today.minusYears(1), today, "MONTH", false);

        // then
        assertThat(result.interval()).isEqualTo("DAY");
        assertThat(result.from()).isEqualTo(today.minusDays(7));
        assertThat(result.subscriptionRequired()).isTrue();
        verify(priceHistoryRepository).findChartByDay(eq(1L), any(), any());
    }

    @Test
    @DisplayName("구독자의 1년 차트는 월별 집계를 유지한다")
    void getChart_subscriberLongPeriod_keepsMonthlyInterval() {
        // given
        LocalDate today = LocalDate.now();
        given(courseRepository.findById(1L)).willReturn(Optional.of(course));
        given(priceHistoryRepository.findChartByMonth(eq(1L), any(), any()))
                .willReturn(List.of());

        // when
        var result = priceService.getChart(
                1L, today.minusYears(1), today, "MONTH", true);

        // then
        assertThat(result.interval()).isEqualTo("MONTH");
        assertThat(result.from()).isEqualTo(today.minusYears(1));
        assertThat(result.subscriptionRequired()).isFalse();
        verify(priceHistoryRepository).findChartByMonth(eq(1L), any(), any());
    }
}
