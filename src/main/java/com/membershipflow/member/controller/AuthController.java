package com.membershipflow.member.controller;

import com.membershipflow.common.security.jwt.JwtTokenProvider;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import com.membershipflow.member.dto.MemberMeResponse;
import com.membershipflow.member.dto.TokenRefreshResponse;
import com.membershipflow.member.entity.OAuth2UserPrincipal;
import com.membershipflow.member.repository.MemberRepository;
import com.membershipflow.member.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
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
    public ResponseEntity<MemberMeResponse> me(
            @AuthenticationPrincipal OAuth2UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(new MemberMeResponse(
                principal.getMemberId(),
                principal.getEmail(),
                principal.getDisplayName() == null ? "" : principal.getDisplayName()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenRefreshResponse> refresh(
            HttpServletRequest request, HttpServletResponse response) {
        String token = extractRefreshTokenCookie(request).orElse(null);
        if (token == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        TokenRefreshResponse result = refreshTokenService.rotate(token)
                .flatMap(rotated -> memberRepository.findById(rotated.memberId())
                        .map(member -> new RotationMember(rotated, member)))
                .map(rotation -> {
                    String newAccess = jwtTokenProvider.createAccessToken(rotation.member());
                    setRefreshCookie(response, rotation.rotated().token());
                    setAccessCookie(response, newAccess);
                    return new TokenRefreshResponse(newAccess);
                })
                .orElse(null);
        if (result == null) {
            clearRefreshCookie(response);
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ResponseEntity.ok(result);
    }

    private record RotationMember(
            RefreshTokenService.RotatedToken rotated,
            com.membershipflow.member.entity.Member member) {
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
