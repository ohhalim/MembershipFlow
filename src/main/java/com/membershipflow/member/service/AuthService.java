package com.membershipflow.member.service;

import com.membershipflow.member.entity.Member;
import com.membershipflow.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final MemberRepository memberRepository;

    /**
     * OAuth2 로그인 결과를 받아 회원을 저장하거나 기존 회원 정보를 갱신한다.
     * (provider, providerId)로 식별한다.
     */
    @Transactional
    public Member saveOrUpdateOAuth2Member(Member incoming) {
        return memberRepository
                .findByProviderAndProviderId(incoming.getProvider(), incoming.getProviderId())
                .map(existing -> {
                    existing.updateOAuth2Profile(
                            incoming.getEmail(), incoming.getName(), incoming.getProfileImageUrl());
                    log.info("OAuth2 member updated: {}", existing.getEmail());
                    return existing; // 영속 상태 → 트랜잭션 종료 시 dirty checking으로 반영
                })
                .orElseGet(() -> {
                    Member saved = memberRepository.save(incoming);
                    log.info("OAuth2 member created: {}", saved.getEmail());
                    return saved;
                });
    }
}
