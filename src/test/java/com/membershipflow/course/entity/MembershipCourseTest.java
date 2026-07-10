package com.membershipflow.course.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipCourseTest {

    private MembershipCourse newCourse() {
        return MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
    }

    @Test
    @DisplayName("latestPriceAt이 없으면(최초 수집) 무조건 갱신된다")
    void updateLatestPrice_firstTime_updates() {
        // given
        MembershipCourse course = newCourse();
        LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 7, 7, 0);

        // when
        course.updateLatestPrice(438_000_000L, "동부회원권", collectedAt);

        // then
        assertThat(course.getLatestPrice()).isEqualTo(438_000_000L);
        assertThat(course.getLatestPriceSource()).isEqualTo("동부회원권");
        assertThat(course.getLatestPriceAt()).isEqualTo(collectedAt);
    }

    @Test
    @DisplayName("기존보다 더 최근에 수집된 값이면 갱신된다 (소스 무관)")
    void updateLatestPrice_newerCollectedAt_updates() {
        // given
        MembershipCourse course = newCourse();
        course.updateLatestPrice(438_000_000L, "동부회원권", LocalDateTime.of(2026, 7, 7, 7, 0));

        // when — 다른 소스지만 더 최근에 수집됨
        course.updateLatestPrice(450_000_000L, "동아골프", LocalDateTime.of(2026, 7, 7, 8, 0));

        // then
        assertThat(course.getLatestPrice()).isEqualTo(450_000_000L);
        assertThat(course.getLatestPriceSource()).isEqualTo("동아골프");
        assertThat(course.getLatestPriceAt()).isEqualTo(LocalDateTime.of(2026, 7, 7, 8, 0));
    }

    @Test
    @DisplayName("기존보다 더 과거에 수집된 값이면 무시된다")
    void updateLatestPrice_olderCollectedAt_isIgnored() {
        // given
        MembershipCourse course = newCourse();
        course.updateLatestPrice(450_000_000L, "동아골프", LocalDateTime.of(2026, 7, 7, 8, 0));

        // when — 더 과거 시점의 값이 뒤늦게 들어옴 (예: 배치/재처리)
        course.updateLatestPrice(100_000_000L, "동부회원권", LocalDateTime.of(2026, 7, 7, 7, 0));

        // then — 무시되고 기존 최신값 유지
        assertThat(course.getLatestPrice()).isEqualTo(450_000_000L);
        assertThat(course.getLatestPriceSource()).isEqualTo("동아골프");
        assertThat(course.getLatestPriceAt()).isEqualTo(LocalDateTime.of(2026, 7, 7, 8, 0));
    }

    @Test
    @DisplayName("동일한 collectedAt이면 무시된다 (더 최신이 아님)")
    void updateLatestPrice_sameCollectedAt_isIgnored() {
        // given
        MembershipCourse course = newCourse();
        LocalDateTime collectedAt = LocalDateTime.of(2026, 7, 7, 8, 0);
        course.updateLatestPrice(450_000_000L, "동아골프", collectedAt);

        // when
        course.updateLatestPrice(999_000_000L, "동부회원권", collectedAt);

        // then
        assertThat(course.getLatestPrice()).isEqualTo(450_000_000L);
        assertThat(course.getLatestPriceSource()).isEqualTo("동아골프");
    }
}
