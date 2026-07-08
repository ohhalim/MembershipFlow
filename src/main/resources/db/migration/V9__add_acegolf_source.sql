-- #136 에이스회원권(acegolf.com) 크롤러 추가 — 4번째 거래소
-- seed: 수집 소스 1건 (에이스회원권)

INSERT INTO crawl_source (name, base_url, crawl_type, active, created_at, updated_at)
VALUES ('에이스회원권', 'https://www.acegolf.com', 'JSOUP', TRUE, NOW(), NOW());
