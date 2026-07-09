-- V3 시드 데이터의 오기 수정: 동부회원권 실제 접속 URL은 dbm-market.co.kr
-- (DongbuCollector는 처음부터 dbm-market.co.kr을 정확히 크롤링해왔음 — crawl_source.base_url 컬럼만 오기)
UPDATE crawl_source
SET base_url = 'http://www.dbm-market.co.kr', updated_at = NOW()
WHERE name = '동부회원권' AND base_url = 'https://www.dongbu114.com';
