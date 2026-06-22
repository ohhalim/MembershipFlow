package com.membershipflow.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 이메일/비밀번호 가입과 OAuth2 소셜 로그인을 함께 수용한다.
 * 스키마는 Flyway V1(기본) + V2(OAuth 컬럼)로 정의되어 있으며 ddl-auto=validate로 검증된다.
 */
@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** 소셜 로그인 회원은 null */
    private String password;

    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OAuth2Provider provider;

    @Column(name = "provider_id")
    private String providerId;

    private String name;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MemberRole role = MemberRole.USER;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.role == null) {
            this.role = MemberRole.USER;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** OAuth2 로그인 시 변경 가능한 프로필 정보를 갱신한다. */
    public void updateOAuth2Profile(String email, String name, String profileImageUrl) {
        this.email = email;
        this.name = name;
        if (this.profileImageUrl == null) {
            this.profileImageUrl = profileImageUrl;
        }
    }
}
