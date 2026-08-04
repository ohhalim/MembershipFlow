-- V18: 최초 결제 외부 호출 선점 및 회원별 활성 결제 시도 단일화
--
-- 이 migration은 기존 PENDING 행의 status, billing_key, order_id를 변경하지 않는다.
-- 활성 행이 중복된 운영 DB에서는 아래 UNIQUE KEY 추가가 의도적으로 실패한다.
-- 배포 전 docs/operations/initial-payment-migration-preflight.md의 조회 결과를 확인하고
-- 수동으로 결제 상태를 대조한 뒤 재실행한다.

ALTER TABLE billing_attempt
    DROP CHECK chk_billing_attempt_status,
    ADD CONSTRAINT chk_billing_attempt_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    ADD COLUMN processing_at DATETIME NULL AFTER expires_at,
    ADD COLUMN active_slot TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_billing_attempt_member_active (member_id, active_slot),
    ADD INDEX idx_billing_attempt_member_status_expiry (member_id, status, expires_at);
