CREATE TABLE batch_heartbeat
(
    batch_name                VARCHAR(64) NOT NULL,
    last_success_epoch_seconds BIGINT      NOT NULL,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (batch_name),
    CONSTRAINT chk_batch_heartbeat_timestamp CHECK (last_success_epoch_seconds >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
