ALTER TABLE coupon
    ADD COLUMN event_code VARCHAR(50) NULL;

UPDATE coupon
SET coupon.event_code = 'ATTENDANCE_2026_FALL'
WHERE event_code IS NULL;

ALTER TABLE coupon
    MODIFY COLUMN event_code VARCHAR (50) NOT NULL;

CREATE INDEX idx_coupon_member_event
    ON coupon (member_id, event_code);
