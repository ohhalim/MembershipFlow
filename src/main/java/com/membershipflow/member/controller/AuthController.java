package com.membershipflow.member.controller;

import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.member.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final MemberRepository memberRepository;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of(
                "id", principal.getMemberId(),
                "email", principal.getEmail(),
                "name", principal.getDisplayName() == null ? "" : principal.getDisplayName()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh(
            HttpServletRequest request, HttpServletResponse response) {
        String token = extractRefreshTokenCookie(request).orElse(null);
        if (token == null) {
            return ResponseEntity.status(401).build();
        }

        return refreshTokenService.findValid(token)
                .flatMap(rt -> memberRepository.findById(rt.getMemberId()))
                .map(member -> {
                    refreshTokenService.delete(token);
                    String newAccess = jwtTokenProvider.createAccessToken(member);
                    String newRefresh = refreshTokenService.create(member.getId());
                    setRefreshCookie(response, newRefresh);
                    setAccessCookie(response, newAccess);
                    return ResponseEntity.ok(Map.of("accessToken", newAccess));
                })
                .orElseGet(() -> {
                    clearRefreshCookie(response);
                    return ResponseEntity.status(401).build();
                });
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        extractRefreshTokenCookie(request).ifPresent(refreshTokenService::delete);
        clearRefreshCookie(response);
        clearAccessCookie(response);
        return ResponseEntity.noContent().build();
    }

    private Optional<String> extractRefreshTokenCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> "refresh_token".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }

    private void setRefreshCookie(HttpServletResponse response, String value) {
        Cookie cookie = new Cookie("refresh_token", value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(refreshTokenService.cookieMaxAgeSeconds());
        response.addCookie(cookie);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie("refresh_token", "");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private void setAccessCookie(HttpServletResponse response, String value) {
        Cookie cookie = accessCookie(value);
        cookie.setMaxAge((int) (jwtTokenProvider.getAccessTokenExpirationMillis() / 1000));
        response.addCookie(cookie);
    }

    private void clearAccessCookie(HttpServletResponse response) {
        Cookie cookie = accessCookie("");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private Cookie accessCookie(String value) {
        Cookie cookie = new Cookie("access_token", value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
