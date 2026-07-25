package com.membershipflow.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.membershipflow.member.entity.RefreshToken;
import com.membershipflow.member.repository.RefreshTokenRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository refreshTokenRepository;
    @InjectMocks RefreshTokenService refreshTokenService;

    @Test
    void rotate_validToken_replacesTokenForSameMember() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenExpirationMs", 2_592_000_000L);
        RefreshToken stored = RefreshToken.builder()
                .memberId(1L)
                .token("old-refresh")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .createdAt(LocalDateTime.now())
                .build();
        given(refreshTokenRepository.findByTokenForUpdate("old-refresh"))
                .willReturn(Optional.of(stored));

        Optional<RefreshTokenService.RotatedToken> result =
                refreshTokenService.rotate("old-refresh");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().memberId()).isEqualTo(1L);
        assertThat(result.orElseThrow().token()).isNotEqualTo("old-refresh");
        then(refreshTokenRepository).should().deleteByMemberId(1L);
        then(refreshTokenRepository).should().save(any(RefreshToken.class));
    }

    @Test
    void rotate_missingToken_doesNotCreateReplacement() {
        given(refreshTokenRepository.findByTokenForUpdate("used-refresh"))
                .willReturn(Optional.empty());

        assertThat(refreshTokenService.rotate("used-refresh")).isEmpty();

        then(refreshTokenRepository).should(never()).deleteByMemberId(any());
        then(refreshTokenRepository).should(never()).save(any());
    }
}
