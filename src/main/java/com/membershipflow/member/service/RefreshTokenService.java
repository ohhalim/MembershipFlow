package com.membershipflow.member.service;

import com.membershipflow.member.entity.RefreshToken;
import com.membershipflow.member.repository.RefreshTokenRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-expiration}")
    private long refreshTokenExpirationMs;

    public String create(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
        String token = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .memberId(memberId)
                .token(token)
                .expiresAt(LocalDateTime.now().plus(refreshTokenExpirationMs, ChronoUnit.MILLIS))
                .createdAt(LocalDateTime.now())
                .build());
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<RefreshToken> findValid(String token) {
        return refreshTokenRepository.findByToken(token)
                .filter(rt -> !rt.isExpired());
    }

    public void delete(String token) {
        refreshTokenRepository.deleteByToken(token);
    }

    public void deleteByMemberId(Long memberId) {
        refreshTokenRepository.deleteByMemberId(memberId);
    }

    public int cookieMaxAgeSeconds() {
        return (int) (refreshTokenExpirationMs / 1000);
    }
}
