-- 대표 현재가 조회 계약(#262) 지원 인덱스.
-- V18은 초기 결제 시도 상태 선점 변경에서 사용하므로 가격 변경은 V19로 분리한다.
-- (course_id, source_id)별 최신 행과 course별 대표 최저가 후보를 함께 읽는 쿼리 대상.

ALTER TABLE price_history
    ADD INDEX idx_price_history_course_source_time_id
        (course_id, source_id, collected_at, id),
    ADD INDEX idx_price_history_course_time_source_id
        (course_id, collected_at, source_id, id);
