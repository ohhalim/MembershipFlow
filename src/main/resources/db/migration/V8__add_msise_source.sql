-- #132 시세닷컴(m-sise.com) 크롤러 추가 — 3번째 거래소
-- seed: 수집 소스 1건 (시세닷컴)

INSERT INTO crawl_source (name, base_url, crawl_type, active, created_at, updated_at)
VALUES ('시세닷컴', 'http://www.m-sise.com', 'JSOUP', TRUE, NOW(), NOW());
