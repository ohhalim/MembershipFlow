package com.membershipflow.subscription.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V18의 active_slot과 V19의 외부 요청 멱등키 UNIQUE를 MySQL에서 검증한다.
 * Docker가 없는 로컬 환경에서는 Testcontainers가 이 테스트만 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class BillingAttemptActiveSlotMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("membershipflow")
            .withUsername("test")
            .withPassword("test");

    @BeforeAll
    static void createSchema() throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE billing_attempt (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        member_id BIGINT NOT NULL,
                        status VARCHAR(20) NOT NULL,
                        expires_at DATETIME NOT NULL,
                        issue_idempotency_key VARCHAR(36) NULL,
                        charge_idempotency_key VARCHAR(36) NULL,
                        active_slot TINYINT GENERATED ALWAYS AS (
                            CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE NULL END
                        ) STORED,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_billing_attempt_member_active (member_id, active_slot),
                        UNIQUE KEY uk_billing_attempt_issue_idempotency (issue_idempotency_key),
                        UNIQUE KEY uk_billing_attempt_charge_idempotency (charge_idempotency_key),
                        CONSTRAINT chk_billing_attempt_status
                            CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'EXPIRED'))
                    ) ENGINE = InnoDB
                    """);
        }
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        if (!MYSQL.isRunning()) return;
        try (Connection connection = MYSQL.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS billing_attempt");
        }
    }

    @Test
    void activeSlot_allowsOnlyOnePendingOrProcessingAttemptPerMember() throws SQLException {
        try (Connection connection = MYSQL.createConnection("")) {
            execute(connection, "INSERT INTO billing_attempt(member_id, status, expires_at) "
                    + "VALUES (1, 'PENDING', '2099-01-01 00:00:00')");

            assertThatThrownBy(() -> execute(connection,
                    "INSERT INTO billing_attempt(member_id, status, expires_at) "
                            + "VALUES (1, 'PROCESSING', '2099-01-01 00:00:00')"))
                    .isInstanceOf(SQLException.class);

            execute(connection, "UPDATE billing_attempt SET status = 'COMPLETED' WHERE member_id = 1");
            execute(connection, "INSERT INTO billing_attempt(member_id, status, expires_at) "
                    + "VALUES (1, 'PENDING', '2099-01-01 00:00:00')");
        }
    }

    @Test
    void idempotencyKeys_cannotBeReusedAcrossAttempts() throws SQLException {
        try (Connection connection = MYSQL.createConnection("")) {
            execute(connection, "INSERT INTO billing_attempt"
                    + "(member_id, status, expires_at, issue_idempotency_key, charge_idempotency_key) "
                    + "VALUES (2, 'COMPLETED', '2099-01-01 00:00:00', 'issue-key', 'charge-key')");

            assertThatThrownBy(() -> execute(connection, "INSERT INTO billing_attempt"
                    + "(member_id, status, expires_at, issue_idempotency_key) "
                    + "VALUES (3, 'COMPLETED', '2099-01-01 00:00:00', 'issue-key')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, "INSERT INTO billing_attempt"
                    + "(member_id, status, expires_at, charge_idempotency_key) "
                    + "VALUES (4, 'COMPLETED', '2099-01-01 00:00:00', 'charge-key')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
