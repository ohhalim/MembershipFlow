-- 거래소별 시세 링크(base_url 폴백)가 도메인 루트로만 연결되던 문제 개선.
-- 개별 코스 상세페이지가 없는 거래소(동부/시세닷컴)는 base_url이 곧 최종 링크이므로,
-- 사이트 메인이 아니라 실제 시세표 페이지로 연결되도록 수정 (컬렉터가 크롤링하는 URL과 동일)
UPDATE crawl_source SET base_url = 'http://www.dbm-market.co.kr/동부회원권/골프회원권/시세', updated_at = NOW()
WHERE name = '동부회원권';

UPDATE crawl_source SET base_url = 'http://www.m-sise.com/page/siseGolfInfo.php', updated_at = NOW()
WHERE name = '시세닷컴';
