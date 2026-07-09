package com.membershipflow.price.service;

import com.membershipflow.collect.entity.CourseSourceMapping;
import com.membershipflow.collect.entity.CrawlSource;
import com.membershipflow.collect.entity.CrawlType;
import com.membershipflow.collect.repository.CourseSourceMappingRepository;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipCourse;
import com.membershipflow.course.entity.MembershipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SourceUrlResolverTest {

    @Mock CourseSourceMappingRepository courseSourceMappingRepository;

    @InjectMocks SourceUrlResolver resolver;

    MembershipCourse course;

    @BeforeEach
    void setUp() {
        course = MembershipCourse.builder()
                .name("88").courseType(CourseType.GOLF)
                .membershipType(MembershipType.REGULAR)
                .build();
    }

    @Test
    @DisplayName("동아골프 매핑이 있으면 custid/code로 조립된 상세 URL을 반환한다")
    void resolve_dongaMapping_buildsDetailUrl() {
        // given
        CrawlSource donga = CrawlSource.builder()
                .name("동아골프").baseUrl("https://www.dongagolf.co.kr/membership/sise/")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        CourseSourceMapping mapping = CourseSourceMapping.builder()
                .course(course).source(donga).sourceKey("10130:1103")
                .build();
        given(courseSourceMappingRepository.findByCourseAndSource(course, donga))
                .willReturn(Optional.of(mapping));

        // when
        String url = resolver.resolve(course, donga);

        // then
        assertThat(url).isEqualTo("https://www.dongagolf.co.kr/membership/info?custid=10130&code=1103");
    }

    @Test
    @DisplayName("에이스회원권 매핑이 있으면 code/m_id로 조립된 상세 URL을 반환한다")
    void resolve_aceMapping_buildsDetailUrl() {
        // given
        CrawlSource ace = CrawlSource.builder()
                .name("에이스회원권").baseUrl("https://www.acegolf.com/membership/i_golf_info_money.html")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        CourseSourceMapping mapping = CourseSourceMapping.builder()
                .course(course).source(ace).sourceKey("e20:e20_p01")
                .build();
        given(courseSourceMappingRepository.findByCourseAndSource(course, ace))
                .willReturn(Optional.of(mapping));

        // when
        String url = resolver.resolve(course, ace);

        // then
        assertThat(url).isEqualTo("https://www.acegolf.com/membership/golf_detail_info.php?code=e20&m_id=e20_p01");
    }

    @Test
    @DisplayName("매핑이 없으면 crawl_source.base_url로 폴백한다 (동부/시세닷컴)")
    void resolve_noMapping_fallsBackToBaseUrl() {
        // given
        CrawlSource dongbu = CrawlSource.builder()
                .name("동부회원권").baseUrl("http://dbm-market.co.kr")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        given(courseSourceMappingRepository.findByCourseAndSource(course, dongbu))
                .willReturn(Optional.empty());

        // when
        String url = resolver.resolve(course, dongbu);

        // then
        assertThat(url).isEqualTo("http://dbm-market.co.kr");
    }

    @Test
    @DisplayName("매핑이 있어도 알 수 없는 소스명이면 base_url로 폴백한다")
    void resolve_unknownSourceWithMapping_fallsBackToBaseUrl() {
        // given
        CrawlSource unknown = CrawlSource.builder()
                .name("알수없는소스").baseUrl("http://example.com")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        CourseSourceMapping mapping = CourseSourceMapping.builder()
                .course(course).source(unknown).sourceKey("a:b")
                .build();
        given(courseSourceMappingRepository.findByCourseAndSource(course, unknown))
                .willReturn(Optional.of(mapping));

        // when
        String url = resolver.resolve(course, unknown);

        // then
        assertThat(url).isEqualTo("http://example.com");
    }

    @Test
    @DisplayName("source_key 형식이 이상하면(구분자 없음) base_url로 폴백한다")
    void resolve_malformedSourceKey_fallsBackToBaseUrl() {
        // given
        CrawlSource donga = CrawlSource.builder()
                .name("동아골프").baseUrl("https://www.dongagolf.co.kr/membership/sise/")
                .crawlType(CrawlType.JSOUP).active(true)
                .build();
        CourseSourceMapping mapping = CourseSourceMapping.builder()
                .course(course).source(donga).sourceKey("malformed")
                .build();
        given(courseSourceMappingRepository.findByCourseAndSource(course, donga))
                .willReturn(Optional.of(mapping));

        // when
        String url = resolver.resolve(course, donga);

        // then
        assertThat(url).isEqualTo("https://www.dongagolf.co.kr/membership/sise/");
    }
}
