package com.membershipflow.attendance.controller;

import com.membershipflow.attendance.service.AttendanceService;
import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.common.security.oauth.CustomOAuth2UserService;
import com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler;
import com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AttendanceController.class)
@Import(SecurityConfig.class)
class AttendanceControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AttendanceService attendanceService;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    @BeforeEach
    void configureMocks() throws Exception {
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());

        willAnswer(inv -> {
            ((HttpServletResponse) inv.getArgument(1)).sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }).given(jwtAuthenticationEntryPoint).commence(any(), any(), any());
    }

    @Test
    @DisplayName("로그인하지 않아도 오늘 참여자 수를 조회할 수 있다")
    void todayParticipantsWithoutLogin() throws Exception {
        // given
        given(attendanceService.countTodayParticipants()).willReturn(42L);

        // when, then
        mockMvc.perform(get("/api/v1/attendances/participants/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(42));
    }

    @Test
    @DisplayName("로그인하지 않으면 출석할 수 없다")
    void attendRequiresLogin() throws Exception {
        // when, then
        mockMvc.perform(post("/api/v1/attendances"))
                .andExpect(status().isUnauthorized());
        verify(attendanceService, never()).attend(any());
    }
}
