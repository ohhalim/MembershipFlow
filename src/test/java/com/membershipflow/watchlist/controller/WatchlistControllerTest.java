package com.membershipflow.watchlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.membershipflow.common.config.SecurityConfig;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.watchlist.dto.WatchlistAddRequest;
import com.membershipflow.watchlist.dto.WatchlistResponse;
import com.membershipflow.watchlist.dto.WatchlistUpdateRequest;
import com.membershipflow.watchlist.service.WatchlistService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

@WebMvcTest(WatchlistController.class)
@Import(SecurityConfig.class)
class WatchlistControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WatchlistService watchlistService;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean com.membershipflow.common.security.oauth.CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler oAuth2FailureHandler;

    private static final Long MEMBER_ID = 1L;

    @BeforeEach
    void configureMocks() throws Exception {
        // JWT 필터가 chain.doFilter()를 호출하도록 설정
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());

        // @AuthenticationPrincipal 주입을 위해 SecurityContext에 principal 설정
        Member member = Member.builder()
                .id(MEMBER_ID)
                .email("test@test.com")
                .role(MemberRole.USER)
                .build();
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(member, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    WatchlistResponse sampleResponse() {
        return new WatchlistResponse(
                10L, 1L, "레이크사이드CC", "경기",
                400_000_000L, true, 438_000_000L,
                LocalDateTime.of(2026, 6, 24, 10, 0));
    }

    @Test
    @DisplayName("POST /api/v1/watchlist — 관심 종목을 추가하면 201과 응답을 반환한다")
    void add_returnsCreated() throws Exception {
        // given
        WatchlistAddRequest req = new WatchlistAddRequest(1L, 400_000_000L, true);
        given(watchlistService.add(eq(MEMBER_ID), any())).willReturn(sampleResponse());

        // when / then
        mockMvc.perform(post("/api/v1/watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.courseName").value("레이크사이드CC"))
                .andExpect(jsonPath("$.targetPrice").value(400_000_000))
                .andExpect(jsonPath("$.alertYn").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/watchlist — 이미 찜한 종목이면 409를 반환한다")
    void add_alreadyExists_returns409() throws Exception {
        // given
        WatchlistAddRequest req = new WatchlistAddRequest(1L, 400_000_000L, true);
        given(watchlistService.add(eq(MEMBER_ID), any()))
                .willThrow(new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS));

        // when / then
        mockMvc.perform(post("/api/v1/watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WATCHLIST_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("POST /api/v1/watchlist — 비구독자 한도 초과 시 403을 반환한다")
    void add_limitExceeded_returns403() throws Exception {
        // given
        WatchlistAddRequest req = new WatchlistAddRequest(1L, 400_000_000L, true);
        given(watchlistService.add(eq(MEMBER_ID), any()))
                .willThrow(new BusinessException(ErrorCode.WATCHLIST_LIMIT_EXCEEDED));

        // when / then
        mockMvc.perform(post("/api/v1/watchlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WATCHLIST_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("GET /api/v1/watchlist — 관심 종목 목록을 반환한다")
    void list_returnsWatchlist() throws Exception {
        // given
        given(watchlistService.list(MEMBER_ID)).willReturn(List.of(sampleResponse()));

        // when / then
        mockMvc.perform(get("/api/v1/watchlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].latestPrice").value(438_000_000));
    }

    @Test
    @DisplayName("PUT /api/v1/watchlist/{id} — 목표가와 알림 설정을 수정한다")
    void update_returnsUpdated() throws Exception {
        // given
        WatchlistUpdateRequest req = new WatchlistUpdateRequest(350_000_000L, false);
        WatchlistResponse updated = new WatchlistResponse(
                10L, 1L, "레이크사이드CC", "경기",
                350_000_000L, false, 438_000_000L,
                LocalDateTime.of(2026, 6, 24, 10, 0));
        given(watchlistService.update(eq(10L), eq(MEMBER_ID), any())).willReturn(updated);

        // when / then
        mockMvc.perform(put("/api/v1/watchlist/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetPrice").value(350_000_000))
                .andExpect(jsonPath("$.alertYn").value(false));
    }

    @Test
    @DisplayName("DELETE /api/v1/watchlist/{id} — 관심 종목을 삭제하면 204를 반환한다")
    void delete_returnsNoContent() throws Exception {
        // given
        willDoNothing().given(watchlistService).delete(10L, MEMBER_ID);

        // when / then
        mockMvc.perform(delete("/api/v1/watchlist/10"))
                .andExpect(status().isNoContent());
    }
}
