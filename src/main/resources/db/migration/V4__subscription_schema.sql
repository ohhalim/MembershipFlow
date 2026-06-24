-- V4: 구독 결제 스키마 (토스페이먼츠 빌링 연동)
-- 실행 순서: subscription_plan → billing_attempt → subscription → payment_history

-- ① subscription_plan: 구독 플랜 마스터 (seed data로 관리)
CREATE TABLE subscription_plan
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    price       INT          NOT NULL,
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_plan_code (code),
    CONSTRAINT chk_plan_code CHECK (code IN ('INDIVIDUAL', 'CORPORATE')),
    CONSTRAINT chk_plan_price CHECK (price > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- ② billing_attempt: 카드 등록 prepare 임시 레코드 (콜백에서 member_id·plan_id 복원용)
CREATE TABLE billing_attempt
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    member_id    BIGINT       NOT NULL,
    plan_id      BIGINT       NOT NULL,
    customer_key VARCHAR(300) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    expires_at   DATETIME     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_attempt_customer_key (customer_key),
    INDEX idx_billing_attempt_member (member_id),
    CONSTRAINT fk_billing_attempt_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_billing_attempt_plan   FOREIGN KEY (plan_id)   REFERENCES subscription_plan (id),
    CONSTRAINT chk_billing_attempt_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'EXPIRED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- ③ subscription: 회원별 구독 상태 (1인 1구독)
CREATE TABLE subscription
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    member_id          BIGINT       NOT NULL,
    plan_id            BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    billing_key        VARCHAR(500) NOT NULL,
    customer_key       VARCHAR(300) NOT NULL,
    card_number_masked VARCHAR(50)  NULL,
    card_company       VARCHAR(50)  NULL,
    fail_count         INT          NOT NULL DEFAULT 0,
    started_at         DATETIME     NOT NULL,
    next_billing_at    DATETIME     NOT NULL,
    cancelled_at       DATETIME     NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_member (member_id),
    UNIQUE KEY uk_subscription_customer_key (customer_key),
    INDEX idx_subscription_billing (status, next_billing_at),

    CONSTRAINT fk_subscription_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_subscription_plan   FOREIGN KEY (plan_id)   REFERENCES subscription_plan (id),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAYMENT_FAILED', 'SUSPENDED')),
    CONSTRAINT chk_fail_count CHECK (fail_count >= 0 AND fail_count <= 3)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- ④ payment_history: 결제 이력 (INSERT only)
CREATE TABLE payment_history
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    member_id        BIGINT       NOT NULL,
    subscription_id  BIGINT       NOT NULL,
    toss_order_id    VARCHAR(64)  NOT NULL,
    toss_payment_key VARCHAR(200) NULL,
    amount           INT          NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    billed_at        DATETIME     NOT NULL,
    fail_reason      VARCHAR(500) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_toss_order (toss_order_id),
    INDEX idx_payment_member_billed (member_id, billed_at),

    CONSTRAINT fk_payment_member       FOREIGN KEY (member_id)       REFERENCES member (id),
    CONSTRAINT fk_payment_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id),
    CONSTRAINT chk_payment_status CHECK (status IN ('SUCCESS', 'FAIL', 'CANCELLED')),
    CONSTRAINT chk_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- seed: 구독 플랜 2종
INSERT INTO subscription_plan (code, name, price, description, active, created_at)
VALUES ('INDIVIDUAL', '개인 구독', 49000, '실시간 알림 + 차트 전체 기간 + 찜 무제한', TRUE, NOW()),
       ('CORPORATE', '법인 구독', 299000, '개인 구독 전체 + 포트폴리오 평가 대시보드 + 다중계정', TRUE, NOW());
