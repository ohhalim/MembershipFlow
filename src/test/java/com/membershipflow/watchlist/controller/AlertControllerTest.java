package com.membershipflow.watchlist.controller;

import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.watchlist.dto.AlertResponse;
import com.membershipflow.watchlist.service.AlertService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AlertController.class)
@Import(SecurityConfig.class)
class AlertControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AlertService alertService;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean com.membershipflow.common.security.oauth.CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void configureMocks() throws Exception {
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());

        Member member = Member.builder()
                .id(MEMBER_ID)
                .email("test@test.com")
                .role(MemberRole.USER)
                .build();
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(member, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("GET /api/v1/alerts — 회원의 알림 이력을 반환한다")
    void list_returnsAlerts() throws Exception {
        // given
        AlertResponse alert = new AlertResponse(
                5L, 1L, "레이크사이드CC",
                398_000_000L, 400_000_000L, "동부회원권",
                LocalDateTime.of(2026, 6, 24, 9, 0), null);
        given(alertService.getAlerts(MEMBER_ID)).willReturn(List.of(alert));

        // when / then
        mockMvc.perform(get("/api/v1/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5))
                .andExpect(jsonPath("$[0].courseName").value("레이크사이드CC"))
                .andExpect(jsonPath("$[0].triggeredPrice").value(398_000_000))
                .andExpect(jsonPath("$[0].readAt").isEmpty());
    }

    @Test
    @DisplayName("PATCH /api/v1/alerts/{id}/read — 알림을 읽음 처리하면 204를 반환한다")
    void markRead_returnsNoContent() throws Exception {
        // given
        willDoNothing().given(alertService).markRead(5L, MEMBER_ID);

        // when / then
        mockMvc.perform(patch("/api/v1/alerts/5/read"))
                .andExpect(status().isNoContent());
    }
}
