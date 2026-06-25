package com.membershipflow.common.security.oauth;

import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.oauth2.redirect-uri}")
    private String redirectUri;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2UserPrincipal principal = (OAuth2UserPrincipal) authentication.getPrincipal();

        String accessToken = jwtTokenProvider.createAccessToken(principal.getMember());
        String refreshToken = refreshTokenService.create(principal.getMemberId());

        Cookie cookie = new Cookie("refresh_token", refreshToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(refreshTokenService.cookieMaxAgeSeconds());
        response.addCookie(cookie);

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("success", "true")
                .queryParam("token", accessToken)
                .build()
                .toUriString();

        log.info("OAuth2 success, redirecting member {} to callback", principal.getEmail());
        response.sendRedirect(targetUrl);
    }
}
