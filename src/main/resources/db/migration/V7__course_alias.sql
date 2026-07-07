-- #130 코스명 정규화·구분 추출로 동아/동부 코스 통합
-- 크롤링 원본 코스명 → 정식 코스명 별칭 테이블

CREATE TABLE course_alias (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    alias_name      VARCHAR(200) NOT NULL,
    canonical_name  VARCHAR(200) NOT NULL,
    membership_type VARCHAR(20)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_alias_name (alias_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 정규화 규칙만으로 처리할 수 없는 별칭 시드
INSERT INTO course_alias (alias_name, canonical_name, membership_type) VALUES
    ('88(팔팔)',                 '88',             'REGULAR'),
    ('88cc',                     '88',             NULL),      -- 구분은 동부 구분 컬럼(개인→REGULAR)을 따름
    ('안성베네스트(구.나다)',    '안성베네스트',   NULL),
    ('레이크우드일반(구.로얄)',  '레이크우드',     'REGULAR'),
    ('플라자설악(舊.설악프라자)', '플라자설악',    NULL),
    ('플라자용인(舊.프라자)',    '플라자용인',     NULL),
    ('한림광릉(광릉포레스트)',   '한림광릉',       NULL),
    ('롯데스카이힐 제주',        '롯데스카이힐제주', NULL);
