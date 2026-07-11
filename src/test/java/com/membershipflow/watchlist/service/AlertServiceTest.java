package com.membershipflow.watchlist.service;

import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.member.entity.Member;
import com.membershipflow.price.entity.PriceHistory;
import com.membershipflow.price.repository.PriceHistoryRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import com.membershipflow.watchlist.entity.Watchlist;
import com.membershipflow.watchlist.repository.AlertLogRepository;
import com.membershipflow.watchlist.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final long SUBSCRIBER_MEMBER_ID     = 1L;
    private static final long NON_SUBSCRIBER_MEMBER_ID = 2L;
    private static final long COURSE_ID = 10L;

    @Mock WatchlistRepository    watchlistRepository;
    @Mock AlertLogRepository     alertLogRepository;
    @Mock PriceHistoryRepository priceHistoryRepository;
    @Mock SimpMessagingTemplate  messagingTemplate;
    @Mock SubscriptionService    subscriptionService;

    @InjectMocks AlertService alertService;

    MembershipCourse course;
    CrawlSource source;

    @BeforeEach
    void setUp() {
        course = MembershipCourse.builder()
                .name("레이크사이드CC")
                .courseType(CourseType.GOLF).membershipType(MembershipType.REGULAR)
                .build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);

        source = CrawlSource.builder()
                .name("동부회원권").baseUrl("http://dbm-market.co.kr")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
    }

    private Watchlist watchlistOf(long memberId, long targetPrice) {
        Member member = Member.builder().id(memberId).email("m" + memberId + "@test.com").build();
        return Watchlist.builder()
                .member(member).course(course)
                .targetPrice(targetPrice).alertYn(true)
                .build();
    }

    private PriceHistory priceHistoryOf(long price) {
        return PriceHistory.builder()
                .course(course).source(source)
                .price(price).collectedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("비구독자의 관심종목은 목표가에 도달해도 알림을 발송하지 않는다 (#174)")
    void checkAndNotify_nonSubscriber_targetReached_doesNotNotify() {
        // given
        Watchlist watchlist = watchlistOf(NON_SUBSCRIBER_MEMBER_ID, 400_000_000L);
        ReflectionTestUtils.setField(watchlist, "id", 101L);
        given(watchlistRepository.findAllAlertEnabled()).willReturn(List.of(watchlist));
        given(subscriptionService.getSubscriberMemberIds(List.of(NON_SUBSCRIBER_MEMBER_ID)))
                .willReturn(Set.of());

        // when
        alertService.checkAndNotify();

        // then
        then(priceHistoryRepository).should(never()).findLatestByCourseIds(any());
        then(alertLogRepository).should(never()).save(any());
        then(messagingTemplate).should(never())
                .convertAndSendToUser(any(), any(), any());
    }

    @Test
    @DisplayName("구독자의 관심종목이 목표가에 도달하면 알림을 발송한다")
    void checkAndNotify_subscriber_targetReached_notifies() {
        // given
        Watchlist watchlist = watchlistOf(SUBSCRIBER_MEMBER_ID, 400_000_000L);
        ReflectionTestUtils.setField(watchlist, "id", 101L);
        given(watchlistRepository.findAllAlertEnabled()).willReturn(List.of(watchlist));
        given(subscriptionService.getSubscriberMemberIds(List.of(SUBSCRIBER_MEMBER_ID)))
                .willReturn(Set.of(SUBSCRIBER_MEMBER_ID));
        given(priceHistoryRepository.findLatestByCourseIds(List.of(COURSE_ID)))
                .willReturn(List.of(priceHistoryOf(380_000_000L)));
        given(alertLogRepository.findWatchlistIdsSentAfter(eq(List.of(101L)), any()))
                .willReturn(List.of());
        given(alertLogRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        alertService.checkAndNotify();

        // then
        then(alertLogRepository).should().save(any());
        then(messagingTemplate).should()
                .convertAndSendToUser(eq(String.valueOf(SUBSCRIBER_MEMBER_ID)), any(), any());
    }

    @Test
    @DisplayName("여러 관심종목의 구독 여부와 중복 알림 이력을 각각 한 번에 조회한다")
    void checkAndNotify_multipleTargets_usesBatchQueries() {
        // given
        Watchlist recentlyNotified = watchlistOf(SUBSCRIBER_MEMBER_ID, 400_000_000L);
        Watchlist notificationTarget = watchlistOf(SUBSCRIBER_MEMBER_ID, 400_000_000L);
        ReflectionTestUtils.setField(recentlyNotified, "id", 101L);
        ReflectionTestUtils.setField(notificationTarget, "id", 102L);

        given(watchlistRepository.findAllAlertEnabled())
                .willReturn(List.of(recentlyNotified, notificationTarget));
        given(subscriptionService.getSubscriberMemberIds(List.of(SUBSCRIBER_MEMBER_ID)))
                .willReturn(Set.of(SUBSCRIBER_MEMBER_ID));
        given(priceHistoryRepository.findLatestByCourseIds(List.of(COURSE_ID)))
                .willReturn(List.of(priceHistoryOf(380_000_000L)));
        given(alertLogRepository.findWatchlistIdsSentAfter(
                eq(List.of(101L, 102L)), any()))
                .willReturn(List.of(101L));
        given(alertLogRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        alertService.checkAndNotify();

        // then
        then(subscriptionService).should()
                .getSubscriberMemberIds(List.of(SUBSCRIBER_MEMBER_ID));
        then(alertLogRepository).should()
                .findWatchlistIdsSentAfter(eq(List.of(101L, 102L)), any());
        then(alertLogRepository).should().save(any());
        then(messagingTemplate).should()
                .convertAndSendToUser(eq(String.valueOf(SUBSCRIBER_MEMBER_ID)), any(), any());
    }
}
