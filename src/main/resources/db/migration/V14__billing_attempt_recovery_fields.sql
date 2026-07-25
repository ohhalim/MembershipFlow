ALTER TABLE billing_attempt
    ADD COLUMN billing_key VARCHAR(500) NULL AFTER created_at,
    ADD COLUMN order_id VARCHAR(64) NULL AFTER billing_key,
    ADD COLUMN card_number_masked VARCHAR(50) NULL AFTER order_id,
    ADD COLUMN card_company VARCHAR(50) NULL AFTER card_number_masked,
    ADD UNIQUE KEY uk_billing_attempt_order_id (order_id);
