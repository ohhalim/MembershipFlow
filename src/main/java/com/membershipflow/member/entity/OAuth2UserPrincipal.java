package com.membershipflow.member.entity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 인증된 회원을 담는 Principal. OAuth2 로그인 흐름과 JWT 필터에서 공통으로 사용한다.
 */
@Getter
public class OAuth2UserPrincipal implements OAuth2User {

    private final Member member;
    private final Map<String, Object> attributes;

    public OAuth2UserPrincipal(Member member, Map<String, Object> attributes) {
        this.member = member;
        this.attributes = attributes;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(member.getRole().getAuthority()));
    }

    @Override
    public String getName() {
        return member.getEmail();
    }

    public Long getMemberId() {
        return member.getId();
    }

    public String getEmail() {
        return member.getEmail();
    }

    public String getDisplayName() {
        return member.getName();
    }
}
