package com.membershipflow.member.controller;

import com.membershipflow.member.entity.OAuth2UserPrincipal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 관련 엔드포인트. /me로 현재 로그인한 회원 정보를 확인할 수 있다.
 * 로그인 시작은 GET /oauth2/authorization/google (Spring Security 기본 제공).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

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
}
