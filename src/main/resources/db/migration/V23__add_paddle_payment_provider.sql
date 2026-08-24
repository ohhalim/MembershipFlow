ALTER TABLE subscription
    ADD COLUMN payment_provider VARCHAR(20) NOT NULL DEFAULT 'TOSS' AFTER status,
    ADD COLUMN external_customer_id VARCHAR(64) NULL AFTER customer_key,
    ADD COLUMN external_subscription_id VARCHAR(64) NULL AFTER external_customer_id,
    ADD COLUMN external_updated_at DATETIME(6) NULL AFTER external_subscription_id,
    MODIFY COLUMN billing_key VARCHAR(500) NULL,
    MODIFY COLUMN customer_key VARCHAR(300) NULL,
    ADD UNIQUE KEY uk_subscription_external_subscription (external_subscription_id),
    ADD CONSTRAINT chk_subscription_payment_provider
        CHECK (payment_provider IN ('TOSS', 'PADDLE'));

ALTER TABLE payment_history
    ADD COLUMN payment_provider VARCHAR(20) NOT NULL DEFAULT 'TOSS' AFTER subscription_id,
    ADD COLUMN external_transaction_id VARCHAR(64) NULL AFTER toss_payment_key,
    MODIFY COLUMN toss_order_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_payment_external_transaction (external_transaction_id),
    ADD CONSTRAINT chk_payment_history_provider
        CHECK (payment_provider IN ('TOSS', 'PADDLE'));

CREATE TABLE paddle_checkout_attempt
(
    id                      VARCHAR(36) NOT NULL,
    member_id               BIGINT      NOT NULL,
    plan_id                 BIGINT      NOT NULL,
    external_transaction_id VARCHAR(64) NULL,
    status                  VARCHAR(20) NOT NULL,
    expires_at              DATETIME    NOT NULL,
    completed_at            DATETIME    NULL,
    created_at              DATETIME    NOT NULL,
    updated_at              DATETIME    NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_paddle_attempt_transaction (external_transaction_id),
    INDEX idx_paddle_attempt_member_status (member_id, status),
    CONSTRAINT fk_paddle_attempt_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_paddle_attempt_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id),
    CONSTRAINT chk_paddle_attempt_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE payment_webhook_event
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    payment_provider  VARCHAR(20) NOT NULL,
    external_event_id VARCHAR(64) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    occurred_at       DATETIME(6) NOT NULL,
    processed_at      DATETIME(6) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_webhook_provider_event (payment_provider, external_event_id),
    CONSTRAINT chk_payment_webhook_provider
        CHECK (payment_provider IN ('TOSS', 'PADDLE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
