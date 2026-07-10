package com.membershipflow.watchlist.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.repository.MembershipCourseRepository;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.price.repository.PriceHistoryRepository;
import com.membershipflow.subscription.service.SubscriptionService;
import com.membershipflow.watchlist.dto.WatchlistAddRequest;
import com.membershipflow.watchlist.repository.WatchlistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WatchlistServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final long COURSE_ID = 10L;

    @Mock WatchlistRepository        watchlistRepository;
    @Mock MemberRepository           memberRepository;
    @Mock MembershipCourseRepository courseRepository;
    @Mock PriceHistoryRepository     priceHistoryRepository;
    @Mock SubscriptionService        subscriptionService;

    @InjectMocks WatchlistService watchlistService;

    Member member;
    MembershipCourse course;
    WatchlistAddRequest req;

    @BeforeEach
    void setUp() {
        member = Member.builder().id(MEMBER_ID).email("a@a.com").build();
        course = MembershipCourse.builder()
                .name("레이크사이드CC")
                .courseType(CourseType.GOLF).membershipType(MembershipType.REGULAR)
                .build();
        ReflectionTestUtils.setField(course, "id", COURSE_ID);
        req = new WatchlistAddRequest(COURSE_ID, 400_000_000L, true);
    }

    @Test
    @DisplayName("비구독자는 5개까지만 찜 등록할 수 있다")
    void add_nonSubscriber_atLimit_throwsLimitExceeded() {
        // given
        given(watchlistRepository.existsByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(false);
        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(watchlistRepository.countByMemberId(MEMBER_ID)).willReturn(5L);

        // when / then
        assertThatThrownBy(() -> watchlistService.add(MEMBER_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.WATCHLIST_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("비구독자는 5개 미만이면 정상 등록된다")
    void add_nonSubscriber_underLimit_succeeds() {
        // given
        given(watchlistRepository.existsByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(false);
        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(false);
        given(watchlistRepository.countByMemberId(MEMBER_ID)).willReturn(4L);
        given(memberRepository.findById(MEMBER_ID)).willReturn(java.util.Optional.of(member));
        given(courseRepository.findById(COURSE_ID)).willReturn(java.util.Optional.of(course));
        given(watchlistRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        var response = watchlistService.add(MEMBER_ID, req);

        // then
        assertThat(response.courseId()).isEqualTo(COURSE_ID);
    }

    @Test
    @DisplayName("구독자는 5개를 초과해도 찜 한도 제한 없이 등록된다 (#173)")
    void add_subscriber_overLimit_stillSucceeds() {
        // given — 이미 10개 찜한 구독자
        given(watchlistRepository.existsByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(false);
        given(subscriptionService.isSubscriber(MEMBER_ID)).willReturn(true);
        given(memberRepository.findById(MEMBER_ID)).willReturn(java.util.Optional.of(member));
        given(courseRepository.findById(COURSE_ID)).willReturn(java.util.Optional.of(course));
        given(watchlistRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // when
        var response = watchlistService.add(MEMBER_ID, req);

        // then — 한도 체크(countByMemberId)가 아예 호출되지 않아야 함
        assertThat(response.courseId()).isEqualTo(COURSE_ID);
        org.mockito.Mockito.verify(watchlistRepository, org.mockito.Mockito.never()).countByMemberId(anyLong());
    }
}
