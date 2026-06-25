package com.membershipflow.common.security.jwt;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.entity.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    // 최소 32바이트 이상 필요 (HMAC-SHA256)
    private static final String SECRET       = "test-secret-key-must-be-at-least-32-bytes!!";
    private static final long   ONE_HOUR_MS  = 3_600_000L;

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, ONE_HOUR_MS);
    }

    private Member member(Long id) {
        return Member.builder()
                .id(id).email("test@example.com").name("테스터").role(MemberRole.USER)
                .build();
    }

    @Test
    @DisplayName("createAccessToken 후 getMemberIdFromToken으로 동일 ID를 반환한다")
    void roundtrip_memberIdPreserved() {
        // given
        Member member = member(42L);

        // when
        String token   = provider.createAccessToken(member);
        Long   memberId = provider.getMemberIdFromToken(token);

        // then
        assertThat(memberId).isEqualTo(42L);
    }

    @Test
    @DisplayName("유효한 토큰은 validateToken이 true를 반환한다")
    void validateToken_valid_returnsTrue() {
        // given
        String token = provider.createAccessToken(member(1L));

        // when
        boolean valid = provider.validateToken(token);

        // then
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 validateToken이 false를 반환한다")
    void validateToken_expired_returnsFalse() throws InterruptedException {
        // given
        JwtTokenProvider shortLived = new JwtTokenProvider(SECRET, 1L);
        String token = shortLived.createAccessToken(member(1L));
        Thread.sleep(10);

        // when
        boolean valid = shortLived.validateToken(token);

        // then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("서명이 변조된 토큰은 validateToken이 false를 반환한다")
    void validateToken_tampered_returnsFalse() {
        // given
        String token    = provider.createAccessToken(member(1L));
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        // when
        boolean valid = provider.validateToken(tampered);

        // then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("빈 문자열은 validateToken이 false를 반환한다")
    void validateToken_blank_returnsFalse() {
        // when
        boolean valid = provider.validateToken("");

        // then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("다른 시크릿으로 서명한 토큰은 validateToken이 false를 반환한다")
    void validateToken_differentSecret_returnsFalse() {
        // given
        JwtTokenProvider other = new JwtTokenProvider(
                "other-secret-key-must-be-at-least-32-bytes!", ONE_HOUR_MS);
        String token = other.createAccessToken(member(1L));

        // when
        boolean valid = provider.validateToken(token);

        // then
        assertThat(valid).isFalse();
    }
}
