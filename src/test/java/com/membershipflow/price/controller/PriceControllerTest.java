package com.membershipflow.price.controller;

import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.price.dto.LatestSourcePriceResponse;
import com.membershipflow.price.dto.PriceChartResponse;
import com.membershipflow.price.dto.PricePointDto;
import com.membershipflow.price.service.PriceService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PriceController.class)
@Import(SecurityConfig.class)
class PriceControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean PriceService priceService;
    @MockitoBean com.membershipflow.subscription.repository.SubscriptionRepository subscriptionRepository;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean com.membershipflow.common.security.oauth.CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    @BeforeEach
    void configureMocks() throws Exception {
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/v1/courses/{id}/prices/latest — 소스별 최신가를 반환한다")
    void latest_returnsSourcePrices() throws Exception {
        // given
        List<LatestSourcePriceResponse> prices = List.of(
                new LatestSourcePriceResponse("동부회원권", "http://dbm-market.co.kr",
                        438_000_000L, LocalDateTime.of(2026, 6, 22, 7, 0)),
                new LatestSourcePriceResponse("동아골프", "https://dongagolf.co.kr",
                        435_000_000L, LocalDateTime.of(2026, 6, 22, 7, 0)));
        given(priceService.getLatestBySource(1L)).willReturn(prices);

        // when / then
        mockMvc.perform(get("/api/v1/courses/1/prices/latest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceName").value("동부회원권"))
                .andExpect(jsonPath("$[0].price").value(438_000_000))
                .andExpect(jsonPath("$[1].sourceName").value("동아골프"));
    }

    @Test
    @DisplayName("GET /api/v1/courses/{id}/prices — 차트 데이터를 반환한다")
    void chart_returnsChartResponse() throws Exception {
        // given
        List<PricePointDto> points = List.of(
                new PricePointDto(LocalDate.of(2026, 6, 15), 420_000_000L, 410_000_000L, 430_000_000L, 2),
                new PricePointDto(LocalDate.of(2026, 6, 22), 438_000_000L, 435_000_000L, 440_000_000L, 1));
        PriceChartResponse chart = new PriceChartResponse(
                1L, "레이크사이드CC", "DAY",
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 22),
                points,
                new PriceChartResponse.Summary(438_000_000L, 420_000_000L, 18_000_000L, 4.29,
                        410_000_000L, 440_000_000L),
                false);
        given(priceService.getChart(eq(1L), any(), any(), anyString(), anyBoolean()))
                .willReturn(chart);

        // when / then
        mockMvc.perform(get("/api/v1/courses/1/prices").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(1))
                .andExpect(jsonPath("$.courseName").value("레이크사이드CC"))
                .andExpect(jsonPath("$.interval").value("DAY"))
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points[0].avgPrice").value(420_000_000))
                .andExpect(jsonPath("$.summary.changeRate").value(4.29))
                .andExpect(jsonPath("$.subscriptionRequired").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 종목의 차트 요청 시 404를 반환한다")
    void chart_courseNotFound_returns404() throws Exception {
        // given
        given(priceService.getChart(eq(999L), any(), any(), anyString(), anyBoolean()))
                .willThrow(new BusinessException(ErrorCode.COURSE_NOT_FOUND));

        // when / then
        mockMvc.perform(get("/api/v1/courses/999/prices"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
    }

    @Test
    @DisplayName("from > to 이면 400을 반환한다")
    void chart_invalidDateRange_returns400() throws Exception {
        // given
        given(priceService.getChart(eq(1L), any(), any(), anyString(), anyBoolean()))
                .willThrow(new BusinessException(ErrorCode.INVALID_DATE_RANGE));

        // when / then
        mockMvc.perform(get("/api/v1/courses/1/prices")
                        .param("from", "2026-06-22")
                        .param("to", "2026-06-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }
}
