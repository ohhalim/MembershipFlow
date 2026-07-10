package com.membershipflow.member.controller;

import com.membershipflow.common.security.jwt.JwtAuthenticationEntryPoint;
import com.membershipflow.common.security.jwt.JwtAuthenticationFilter;
import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.common.security.oauth.CustomOAuth2UserService;
import com.membershipflow.common.security.oauth.OAuth2AuthenticationFailureHandler;
import com.membershipflow.common.security.oauth.OAuth2AuthenticationSuccessHandler;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.entity.RefreshToken;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.member.service.RefreshTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OAuth2 로그인이 없는 최소 보안 체인을 @Order(1)로 앞세워서
 * EntryPoint 동작을 결정론적으로 테스트한다.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class AuthControllerTest {

    // ── SecurityConfig의 OAuth2 로그인이 EntryPoint보다 먼저 잡는 문제를 회피한다.
    // @Order(1)로 모든 요청을 가장 먼저 처리해 OAuth2 리다이렉트가 끼어들지 않게 한다.
    @TestConfiguration
    static class TestSecurity {
        @Bean
        @Order(1)
        SecurityFilterChain testChain(
                HttpSecurity http,
                JwtAuthenticationFilter jwtFilter,
                JwtAuthenticationEntryPoint entryPoint) throws Exception {
            return http
                    .securityMatcher("/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                    .authorizeHttpRequests(a -> a
                            .requestMatchers("/api/v1/auth/**").authenticated()
                            .anyRequest().permitAll())
                    .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                    .build();
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockitoBean OAuth2AuthenticationFailureHandler oAuth2FailureHandler;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean MemberRepository memberRepository;
    @MockitoBean RefreshTokenService refreshTokenService;

    @BeforeEach
    void configureMocks() throws Exception {
        // 필터 모의 객체는 체인을 그대로 통과시킨다 (no-op이면 컨트롤러에 도달하지 못함)
        willAnswer(inv -> {
            ((FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).given(jwtAuthenticationFilter).doFilter(any(), any(), any());

        // EntryPoint 모의 객체는 401을 반환한다
        willAnswer(inv -> {
            ((HttpServletResponse) inv.getArgument(1))
                    .sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }).given(jwtAuthenticationEntryPoint).commence(any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────────

    private OAuth2UserPrincipal principal(Long id, String email, String name) {
        Member member = Member.builder()
                .id(id).email(email).name(name).role(MemberRole.USER).build();
        return new OAuth2UserPrincipal(member, null);
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // tests
    // ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증된 요청은 200과 회원 정보를 반환한다")
    void me_authenticated_returns200WithMemberInfo() throws Exception {
        // given
        OAuth2UserPrincipal p = principal(1L, "test@example.com", "홍길동");
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/auth/me").with(authentication(auth)));

        // then
        result.andExpect(status().isOk())
              .andExpect(jsonPath("$.id", is(1)))
              .andExpect(jsonPath("$.email", is("test@example.com")))
              .andExpect(jsonPath("$.name", is("홍길동")));
    }

    @Test
    @DisplayName("name이 null인 회원은 빈 문자열을 반환한다")
    void me_nullName_returnsEmptyString() throws Exception {
        // given
        OAuth2UserPrincipal p = principal(2L, "noname@example.com", null);
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/auth/me").with(authentication(auth)));

        // then
        result.andExpect(status().isOk())
              .andExpect(jsonPath("$.name", is("")));
    }

    @Test
    @DisplayName("미인증 요청은 401을 반환한다")
    void me_unauthenticated_returns401() throws Exception {
        // given: 인증 정보 없음

        // when
        ResultActions result = mockMvc.perform(
                get("/api/v1/auth/me").accept(MediaType.APPLICATION_JSON));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh 성공 시 응답 본문의 액세스 토큰과 함께 access_token 쿠키도 설정된다")
    void refresh_success_setsAccessTokenCookie() throws Exception {
        // given
        Member member = Member.builder()
                .id(1L).email("test@example.com").name("홍길동").role(MemberRole.USER).build();
        OAuth2UserPrincipal p = new OAuth2UserPrincipal(member, null);
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());

        RefreshToken stored = RefreshToken.builder()
                .memberId(1L).token("old-refresh")
                .expiresAt(LocalDateTime.now().plusDays(30))
                .createdAt(LocalDateTime.now())
                .build();
        given(refreshTokenService.findValid("old-refresh")).willReturn(Optional.of(stored));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.createAccessToken(member)).willReturn("new-access");
        given(refreshTokenService.create(1L)).willReturn("new-refresh");
        given(refreshTokenService.cookieMaxAgeSeconds()).willReturn(2592000);
        given(jwtTokenProvider.getAccessTokenExpirationMillis()).willReturn(3600000L);

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/auth/refresh")
                        .with(authentication(auth))
                        .cookie(new Cookie("refresh_token", "old-refresh")));

        // then
        result.andExpect(status().isOk())
              .andExpect(jsonPath("$.accessToken", is("new-access")))
              .andExpect(cookie().value("refresh_token", "new-refresh"))
              .andExpect(cookie().value("access_token", "new-access"))
              .andExpect(cookie().httpOnly("access_token", true))
              .andExpect(cookie().path("access_token", "/"))
              .andExpect(cookie().maxAge("access_token", 3600))
              .andExpect(cookie().attribute("access_token", "SameSite", "Lax"));
    }

    @Test
    @DisplayName("refresh_token 쿠키가 없으면 refresh는 401을 반환한다")
    void refresh_withoutCookie_returns401() throws Exception {
        // given
        OAuth2UserPrincipal p = principal(1L, "test@example.com", "홍길동");
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/auth/refresh").with(authentication(auth)));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout 시 refresh_token과 access_token 쿠키가 모두 삭제된다")
    void logout_clearsBothCookies() throws Exception {
        // given
        OAuth2UserPrincipal p = principal(1L, "test@example.com", "홍길동");
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());

        // when
        ResultActions result = mockMvc.perform(
                post("/api/v1/auth/logout")
                        .with(authentication(auth))
                        .cookie(new Cookie("refresh_token", "some-refresh")));

        // then
        result.andExpect(status().isNoContent())
              .andExpect(cookie().maxAge("refresh_token", 0))
              .andExpect(cookie().value("refresh_token", ""))
              .andExpect(cookie().maxAge("access_token", 0))
              .andExpect(cookie().value("access_token", ""))
              .andExpect(cookie().httpOnly("access_token", true))
              .andExpect(cookie().path("access_token", "/"));
    }
}
