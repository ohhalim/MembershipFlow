package com.membershipflow.collect.service;

import com.membershipflow.collect.collector.CollectException;
import com.membershipflow.collect.collector.CollectedPrice;
import com.membershipflow.collect.collector.CollectorRegistry;
import com.membershipflow.collect.collector.PriceCollector;
import com.membershipflow.collect.entity.CollectRun;
import com.membershipflow.collect.entity.CollectStatus;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CollectRunRepository;
import com.membershipflow.collect.repository.CrawlSourceRepository;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
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
    @Mock CollectRunRepository        collectRunRepository;
    @Mock MembershipCourseRepository  membershipCourseRepository;
    @Mock PriceHistoryRepository      priceHistoryRepository;
    @Mock CollectorRegistry           collectorRegistry;
    @Mock PriceCollector              collector;

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
                .name("레이크사이드CC").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR).holes(18)
                .build();

        given(collector.collect()).willReturn(List.of(cp));
        given(collectRunRepository.save(any())).willReturn(run);
        given(membershipCourseRepository.findByNameAndCourseTypeAndMembershipType(
                "레이크사이드CC", CourseType.GOLF, MembershipType.REGULAR))
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
