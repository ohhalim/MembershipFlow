package com.membershipflow.course.controller;

import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.course.dto.CourseDetailResponse;
import com.membershipflow.course.dto.CourseListItemResponse;
import com.membershipflow.course.dto.RankingItemResponse;
import com.membershipflow.course.entity.CourseType;
import com.membershipflow.course.entity.MembershipType;
import com.membershipflow.course.service.CourseService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
class CourseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean CourseService courseService;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean com.membershipflow.common.security.oauth.CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    CourseListItemResponse sampleItem;
    CourseDetailResponse sampleDetail;

    @BeforeEach
    void configureMocks() throws Exception {
        // JwtAuthenticationFilter 목이 chain.doFilter()를 호출해야 컨트롤러에 요청이 전달됨
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @BeforeEach
    void setUp() {
        sampleItem = new CourseListItemResponse(
                1L, "레이크사이드CC", "경기",
                "GOLF", "REGULAR", 18,
                438_000_000L, "2026-06-22T07:00", 2.5);

        sampleDetail = new CourseDetailResponse(
                1L, "레이크사이드CC", "경기",
                "GOLF", "REGULAR", 18,
                List.of(new CourseDetailResponse.SourcePrice(
                        "동부회원권", "http://dbm-market.co.kr",
                        438_000_000L, "2026-06-22T07:00", true)),
                false, null);
    }

    @Test
    @DisplayName("GET /api/v1/courses — 종목 목록을 반환한다")
    void list_returnsPagedCourses() throws Exception {
        // given
        given(courseService.search(any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(sampleItem), PageRequest.of(0, 20), 1));

        // when / then
        mockMvc.perform(get("/api/v1/courses").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("레이크사이드CC"))
                .andExpect(jsonPath("$.content[0].latestPrice").value(438_000_000))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/courses?q=레이크 — 이름 필터가 서비스로 전달된다")
    void list_withQuery_passesFilterToService() throws Exception {
        // given
        given(courseService.search(eq("레이크"), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(sampleItem), PageRequest.of(0, 20), 1));

        // when / then
        mockMvc.perform(get("/api/v1/courses").param("q", "레이크"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("레이크사이드CC"));
    }

    @Test
    @DisplayName("GET /api/v1/courses/{id} — 종목 상세를 반환한다")
    void detail_returnsDetailWithLatestPrices() throws Exception {
        // given
        given(courseService.getDetail(1L)).willReturn(sampleDetail);

        // when / then
        mockMvc.perform(get("/api/v1/courses/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sources[0].sourceName").value("동부회원권"))
                .andExpect(jsonPath("$.sources[0].price").value(438_000_000))
                .andExpect(jsonPath("$.watchlisted").value(false));
    }

    @Test
    @DisplayName("GET /api/v1/courses/ranking — 랭킹을 반환한다")
    void ranking_returnsRankedList() throws Exception {
        // given
        RankingItemResponse item = new RankingItemResponse(
                1, 1L, "레이크사이드CC", "경기",
                CourseType.GOLF, MembershipType.REGULAR,
                438_000_000L, 420_000_000L, 4.29, 18_000_000L);
        given(courseService.getRanking(any(), any(), any(), anyInt(), anyInt()))
                .willReturn(new com.membershipflow.course.dto.RankingPageResponse(
                        List.of(item), 0, 20, 1L, false));

        // when / then
        mockMvc.perform(get("/api/v1/courses/ranking").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rank").value(1))
                .andExpect(jsonPath("$.content[0].name").value("레이크사이드CC"))
                .andExpect(jsonPath("$.content[0].changeRate").value(4.29));
    }

    @Test
    @DisplayName("존재하지 않는 종목 조회 시 404를 반환한다")
    void detail_notFound_returns404() throws Exception {
        // given
        given(courseService.getDetail(999L))
                .willThrow(new com.membershipflow.common.exception.BusinessException(
                        com.membershipflow.common.exception.ErrorCode.COURSE_NOT_FOUND));

        // when / then
        mockMvc.perform(get("/api/v1/courses/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
    }
}
