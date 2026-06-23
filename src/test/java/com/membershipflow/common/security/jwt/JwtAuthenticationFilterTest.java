package com.membershipflow.common.security.jwt;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock MemberRepository memberRepository;
    @InjectMocks JwtAuthenticationFilter filter;

    MockHttpServletRequest request;
    MockHttpServletResponse response;
    FilterChain chain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request  = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain    = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Member member(Long id) {
        return Member.builder().id(id).email("user@test.com").role(MemberRole.USER).build();
    }

    @Test
    @DisplayName("유효한 토큰이면 SecurityContext에 인증이 설정되고 체인이 계속된다")
    void validToken_setsAuthentication() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer valid-token");
        given(jwtTokenProvider.validateToken("valid-token")).willReturn(true);
        given(jwtTokenProvider.getMemberIdFromToken("valid-token")).willReturn(1L);
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L)));

        // when
        filter.doFilter(request, response, chain);

        // then
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((OAuth2UserPrincipal) auth.getPrincipal()).getMemberId()).isEqualTo(1L);
        then(chain).should().doFilter(request, response);
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 SecurityContext가 비어있고 체인은 계속된다")
    void invalidToken_noAuth_chainContinues() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer bad-token");
        given(jwtTokenProvider.validateToken("bad-token")).willReturn(false);

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        then(chain).should().doFilter(request, response);
        then(memberRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 인증 없이 체인이 계속된다")
    void noHeader_noAuth_chainContinues() throws Exception {
        // given: Authorization 헤더 없음

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        then(chain).should().doFilter(request, response);
        then(jwtTokenProvider).shouldHaveNoInteractions();
        then(memberRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("토큰이 유효하지만 DB에 회원이 없으면 인증 없이 체인이 계속된다")
    void validTokenButMemberNotFound_noAuth() throws Exception {
        // given
        request.addHeader("Authorization", "Bearer valid-token");
        given(jwtTokenProvider.validateToken("valid-token")).willReturn(true);
        given(jwtTokenProvider.getMemberIdFromToken("valid-token")).willReturn(99L);
        given(memberRepository.findById(99L)).willReturn(Optional.empty());

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        then(chain).should().doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer 접두사 없는 헤더는 토큰으로 인식하지 않는다")
    void headerWithoutBearerPrefix_noAuth() throws Exception {
        // given
        request.addHeader("Authorization", "Basic abc123");

        // when
        filter.doFilter(request, response, chain);

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        then(jwtTokenProvider).shouldHaveNoInteractions();
        then(memberRepository).shouldHaveNoInteractions();
    }
}
