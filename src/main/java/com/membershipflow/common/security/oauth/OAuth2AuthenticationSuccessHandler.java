package com.membershipflow.common.security.oauth;

import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth2 로그인 성공 시 JWT access token을 발급하고 프런트 콜백 URL로 리다이렉트한다.
 * (MVP: refresh token/쿠키 없이 access token만 쿼리 파라미터로 전달)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2UserPrincipal principal = (OAuth2UserPrincipal) authentication.getPrincipal();
        String accessToken = jwtTokenProvider.createAccessToken(principal.getMember());

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("success", "true")
                .queryParam("token", accessToken)
                .build()
                .toUriString();

        log.info("OAuth2 success, redirecting member {} to callback", principal.getEmail());
        response.sendRedirect(targetUrl);
    }
}
