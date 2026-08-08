-- 기존 구독은 기존 플랜과 가격을 유지하고, 신규 가입부터 월간/연간 플랜을 사용한다.
ALTER TABLE subscription_plan
    DROP CHECK chk_plan_code,
    ADD COLUMN billing_cycle VARCHAR(20) NULL AFTER price;

UPDATE subscription_plan
SET billing_cycle = 'MONTHLY',
    active = FALSE
WHERE code IN ('INDIVIDUAL', 'CORPORATE');

ALTER TABLE subscription_plan
    MODIFY COLUMN billing_cycle VARCHAR(20) NOT NULL,
    ADD CONSTRAINT chk_plan_code
        CHECK (code IN ('INDIVIDUAL', 'CORPORATE', 'MONTHLY', 'ANNUAL')),
    ADD CONSTRAINT chk_plan_billing_cycle
        CHECK (billing_cycle IN ('MONTHLY', 'ANNUAL'));

INSERT INTO subscription_plan
    (code, name, price, billing_cycle, description, active, created_at)
VALUES
    ('MONTHLY', '월간 구독', 10000, 'MONTHLY',
     '실시간 알림 + 차트 전체 기간 + 찜 무제한', TRUE, NOW()),
    ('ANNUAL', '연간 구독', 90000, 'ANNUAL',
     '실시간 알림 + 차트 전체 기간 + 찜 무제한', TRUE, NOW());
