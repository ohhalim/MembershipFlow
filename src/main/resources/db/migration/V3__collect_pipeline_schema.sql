-- V3: 수집 파이프라인 스키마 갭 해소
-- 실행 순서 준수 (FK 의존): collect_run 생성 → price_history ALTER → course_source_mapping 생성

-- ① crawl_source: crawl_type · updated_at 추가
ALTER TABLE crawl_source
    ADD COLUMN crawl_type VARCHAR(20) NOT NULL DEFAULT 'JSOUP' AFTER base_url,
    ADD COLUMN updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

-- ② membership_course: type→course_type, source_id 제거, membership_type·holes·active 추가
ALTER TABLE membership_course
    DROP FOREIGN KEY fk_course_source,
    DROP COLUMN source_id,
    CHANGE COLUMN type course_type VARCHAR(20) NOT NULL,
    ADD COLUMN membership_type VARCHAR(20)      NOT NULL DEFAULT 'REGULAR' AFTER course_type,
    ADD COLUMN holes           TINYINT UNSIGNED NULL     AFTER membership_type,
    ADD COLUMN active          BOOLEAN          NOT NULL DEFAULT TRUE AFTER holes,
    DROP INDEX idx_membership_course_type,
    ADD INDEX  idx_membership_course_type   (course_type),
    ADD INDEX  idx_membership_course_region (region),
    ADD UNIQUE KEY uk_course_name_type_membership (name, course_type, membership_type);

-- ③-a collect_run: price_history FK 참조원 — 반드시 price_history ALTER 전에 생성
CREATE TABLE collect_run
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    source_id      BIGINT        NOT NULL,
    started_at     DATETIME      NOT NULL,
    finished_at    DATETIME      NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    success_count  INT           NOT NULL DEFAULT 0,
    fail_count     INT           NOT NULL DEFAULT 0,
    error_message  VARCHAR(1000) NULL,
    parser_version VARCHAR(20)   NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_collect_run_source (source_id, started_at),
    CONSTRAINT fk_collect_run_source FOREIGN KEY (source_id) REFERENCES crawl_source (id),
    CONSTRAINT chk_collect_run_status CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAIL'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- ③ price_history: FK 잠시 해제 후 source_id NOT NULL 강화, collect_run_id FK 추가
ALTER TABLE price_history
    DROP FOREIGN KEY fk_price_source;

ALTER TABLE price_history
    MODIFY COLUMN source_id BIGINT NOT NULL,
    ADD COLUMN collect_run_id BIGINT NULL AFTER price,
    ADD INDEX idx_price_history_source_time (source_id, collected_at),
    ADD CONSTRAINT fk_price_source FOREIGN KEY (source_id) REFERENCES crawl_source (id),
    ADD CONSTRAINT fk_price_collect_run FOREIGN KEY (collect_run_id) REFERENCES collect_run (id);

-- ③-b course_source_mapping: 소스별 종목 고유 키 (동부 sidx, 동아 custid:code)
CREATE TABLE course_source_mapping
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    course_id  BIGINT       NOT NULL,
    source_id  BIGINT       NOT NULL,
    source_key VARCHAR(100) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_course_source (course_id, source_id),
    INDEX idx_csm_source (source_id),
    CONSTRAINT fk_csm_course FOREIGN KEY (course_id) REFERENCES membership_course (id),
    CONSTRAINT fk_csm_source FOREIGN KEY (source_id) REFERENCES crawl_source (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- ④ member: provider · provider_id NOT NULL 강화, updated_at NOT NULL
ALTER TABLE member
    MODIFY COLUMN provider    VARCHAR(20)  NOT NULL DEFAULT 'GOOGLE',
    MODIFY COLUMN provider_id VARCHAR(255) NOT NULL,
    MODIFY COLUMN updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- ⑤ watchlist: updated_at 추가, 알림 체크 인덱스 추가
ALTER TABLE watchlist
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
    ADD INDEX idx_watchlist_alert (alert_yn, target_price);

-- ⑥ alert_log: price→triggered_price rename, source_id FK, read_at 추가
ALTER TABLE alert_log
    CHANGE COLUMN price triggered_price BIGINT NOT NULL,
    ADD COLUMN source_id BIGINT   NOT NULL AFTER triggered_price,
    ADD COLUMN read_at   DATETIME NULL    AFTER sent_at,
    DROP INDEX idx_alert_log_watchlist,
    ADD INDEX idx_alert_log_watchlist_sent (watchlist_id, sent_at),
    ADD INDEX idx_alert_log_read (watchlist_id, read_at),
    ADD CONSTRAINT fk_alert_source FOREIGN KEY (source_id) REFERENCES crawl_source (id);

-- seed: 수집 소스 2건 (동부회원권, 동아골프)
INSERT INTO crawl_source (name, base_url, crawl_type, active, created_at, updated_at)
VALUES ('동부회원권', 'https://www.dongbu114.com', 'JSOUP', TRUE, NOW(), NOW()),
       ('동아골프', 'https://www.dongagolf.co.kr', 'JSOUP', TRUE, NOW(), NOW());
