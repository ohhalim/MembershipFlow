-- 프리미엄회원권 공개 홈페이지 수집 소스 추가
-- 신규 종목 자동 등록은 비활성화: 기존 종목과 정확히 매칭되는 시세만 비교에 사용

ALTER TABLE crawl_source
    ADD COLUMN allow_new_courses BOOLEAN NOT NULL DEFAULT TRUE AFTER active;

INSERT INTO crawl_source
    (name, base_url, crawl_type, active, allow_new_courses, created_at, updated_at)
VALUES
    ('프리미엄회원권', 'https://www.premiumgolf.co.kr/', 'JSOUP', TRUE, FALSE, NOW(), NOW());
