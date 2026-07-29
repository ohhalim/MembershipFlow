-- 공개 시세를 제공하는 회원권 쿨거래 수집 소스 추가
-- 신규 종목 자동 등록은 비활성화: 기존 종목과 정확히 매칭되는 시세만 비교에 사용

INSERT INTO crawl_source
    (name, base_url, crawl_type, active, allow_new_courses, created_at, updated_at)
VALUES
    ('회원권 쿨거래', 'https://coolmembership.co.kr/', 'JSOUP', TRUE, FALSE, NOW(), NOW());
