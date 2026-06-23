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
import com.membershipflow.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
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
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
}
