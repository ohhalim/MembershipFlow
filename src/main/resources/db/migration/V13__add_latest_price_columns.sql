-- 가격 비정규화 컬럼 추가 (#100).
-- 정렬(price_asc/price_desc/latest)·랭킹·요약·이상탐지 등에서 매번 price_history 전체를
-- ROW_NUMBER() 윈도우 함수로 JOIN하던 부하를 없애기 위해, 코스별 "가장 최근에 수집된"
-- 가격을 membership_course에 비정규화해 둔다 (소스 무관 최신 수집 기준).
ALTER TABLE membership_course
    ADD COLUMN latest_price BIGINT NULL,
    ADD COLUMN latest_price_source VARCHAR(50) NULL,
    ADD COLUMN latest_price_at DATETIME NULL;

-- 기존 데이터 백필: price_history에서 코스별 최신 1건(collected_at DESC, 동률 시 id DESC)을 찾아 채운다.
-- PriceHistoryRepository#findLatestByCourseIds의 tie-break 기준과 동일하게 맞춰,
-- 이후 CollectService가 갱신하는 값과 어긋나지 않도록 한다.
UPDATE membership_course c
JOIN (
    SELECT ph.course_id, ph.price, cs.name AS source_name, ph.collected_at
    FROM (
        SELECT id, course_id, source_id, price, collected_at,
               ROW_NUMBER() OVER (PARTITION BY course_id ORDER BY collected_at DESC, id DESC) AS rn
        FROM price_history
    ) ph
    JOIN crawl_source cs ON cs.id = ph.source_id
    WHERE ph.rn = 1
) latest ON latest.course_id = c.id
SET c.latest_price        = latest.price,
    c.latest_price_source = latest.source_name,
    c.latest_price_at     = latest.collected_at;
