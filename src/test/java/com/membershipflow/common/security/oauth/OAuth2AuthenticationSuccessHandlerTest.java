package com.membershipflow.common.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.service.RefreshTokenService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    JwtTokenProvider jwtTokenProvider;

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    Authentication authentication;

    OAuth2AuthenticationSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        successHandler = new OAuth2AuthenticationSuccessHandler(jwtTokenProvider, refreshTokenService);
        ReflectionTestUtils.setField(successHandler, "redirectUri", "https://membershipflow.site/auth/callback");
        ReflectionTestUtils.setField(successHandler, "cookieSecure", true);
    }

    @Test
    @DisplayName("OAuth 로그인 성공 시 토큰을 HttpOnly 쿠키로 발급하고 URL에는 노출하지 않는다")
    void redirectsWithoutAccessTokenQueryParameter() throws Exception {
        Member member = Member.builder()
                .id(1L)
                .email("member@example.com")
                .name("회원")
                .role(MemberRole.USER)
                .build();
        OAuth2UserPrincipal principal = new OAuth2UserPrincipal(member, Map.of());
        given(authentication.getPrincipal()).willReturn(principal);
        given(jwtTokenProvider.createAccessToken(member)).willReturn("access-token");
        given(jwtTokenProvider.getAccessTokenExpirationMillis()).willReturn(3_600_000L);
        given(refreshTokenService.create(1L)).willReturn("refresh-token");
        given(refreshTokenService.cookieMaxAgeSeconds()).willReturn(604_800);

        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                new MockHttpServletRequest(), response, authentication);

        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://membershipflow.site/auth/callback?success=true");
        assertThat(response.getCookie("access_token")).isNotNull();
        assertThat(response.getCookie("access_token").getValue()).isEqualTo("access-token");
        assertThat(response.getCookie("access_token").isHttpOnly()).isTrue();
        assertThat(response.getCookie("refresh_token")).isNotNull();
        assertThat(response.getCookie("refresh_token").getValue()).isEqualTo("refresh-token");
        assertThat(response.getCookie("refresh_token").isHttpOnly()).isTrue();
    }
}
