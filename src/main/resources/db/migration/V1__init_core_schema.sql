-- MembershipFlow 코어 스키마

-- 수집 소스 (회원권 거래소)
CREATE TABLE crawl_source (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    base_url    VARCHAR(500) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crawl_source_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 회원권 종목
CREATE TABLE membership_course (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(200) NOT NULL,           -- 골프장/리조트명
    region      VARCHAR(100),                    -- 지역
    type        VARCHAR(50)  NOT NULL,           -- GOLF / CONDO / FITNESS ...
    source_id   BIGINT,                          -- 최초 수집 소스
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_membership_course_type (type),
    KEY idx_membership_course_name (name),
    CONSTRAINT fk_course_source FOREIGN KEY (source_id) REFERENCES crawl_source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 시세 이력 (시계열)
CREATE TABLE price_history (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    course_id     BIGINT      NOT NULL,
    price         BIGINT      NOT NULL,          -- 원 단위
    collected_at  DATETIME    NOT NULL,
    source_id     BIGINT,
    PRIMARY KEY (id),
    KEY idx_price_history_course_time (course_id, collected_at),
    CONSTRAINT fk_price_course FOREIGN KEY (course_id) REFERENCES membership_course (id),
    CONSTRAINT fk_price_source FOREIGN KEY (source_id) REFERENCES crawl_source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 회원
CREATE TABLE member (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    nickname    VARCHAR(100),
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 관심 종목 (찜 + 목표가)
CREATE TABLE watchlist (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    member_id     BIGINT   NOT NULL,
    course_id     BIGINT   NOT NULL,
    target_price  BIGINT,
    alert_yn      BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_watchlist_member_course (member_id, course_id),
    CONSTRAINT fk_watchlist_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_watchlist_course FOREIGN KEY (course_id) REFERENCES membership_course (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 알림 발송 이력
CREATE TABLE alert_log (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    watchlist_id  BIGINT   NOT NULL,
    price         BIGINT   NOT NULL,
    sent_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_alert_log_watchlist (watchlist_id),
    CONSTRAINT fk_alert_watchlist FOREIGN KEY (watchlist_id) REFERENCES watchlist (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
