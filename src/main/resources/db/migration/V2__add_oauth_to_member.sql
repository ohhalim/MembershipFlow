-- 소셜 로그인(OAuth2) 지원: member 테이블 확장
-- 기존 이메일/비밀번호 가입 외에 구글 등 OAuth2 로그인을 수용한다.
ALTER TABLE member
    MODIFY COLUMN password VARCHAR(255) NULL,                       -- 소셜 로그인은 비밀번호 없음
    ADD COLUMN provider          VARCHAR(20)  NULL AFTER nickname,  -- GOOGLE / KAKAO ...
    ADD COLUMN provider_id       VARCHAR(255) NULL AFTER provider,  -- provider가 발급한 고유 ID (구글 sub)
    ADD COLUMN name              VARCHAR(100) NULL AFTER provider_id,
    ADD COLUMN profile_image_url VARCHAR(500) NULL AFTER name,
    ADD COLUMN role              VARCHAR(20)  NOT NULL DEFAULT 'USER' AFTER profile_image_url,
    ADD COLUMN updated_at        DATETIME     NULL AFTER created_at,
    ADD UNIQUE KEY uk_member_provider (provider, provider_id);
