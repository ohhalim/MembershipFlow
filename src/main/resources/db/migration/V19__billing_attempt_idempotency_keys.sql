-- Toss POST 요청의 응답 유실 시 동일 요청을 안전하게 재호출하기 위한 멱등키
-- 기존 PROCESSING 행은 키가 없으므로 결제 승인 재호출 없이 주문 조회로만 복구한다.

ALTER TABLE billing_attempt
    ADD COLUMN issue_idempotency_key VARCHAR(36) NULL AFTER processing_at,
    ADD COLUMN charge_idempotency_key VARCHAR(36) NULL AFTER order_id,
    ADD UNIQUE KEY uk_billing_attempt_issue_idempotency (issue_idempotency_key),
    ADD UNIQUE KEY uk_billing_attempt_charge_idempotency (charge_idempotency_key);
