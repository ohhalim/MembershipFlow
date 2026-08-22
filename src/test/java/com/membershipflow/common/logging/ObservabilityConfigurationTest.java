package com.membershipflow.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTest {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    @DisplayName("JSON 로그는 Logback context를 제외하고 request_id만 MDC에서 포함한다")
    void jsonLog_excludesContextAndIncludesRequestId() throws IOException {
        String logback = read("src/main/resources/logback-spring.xml");

        assertThat(logback)
                .contains("<includeContext>false</includeContext>")
                .contains("<includeMdcKeyName>request_id</includeMdcKeyName>");
    }

    @Test
    @DisplayName("Alloy는 고정된 저카디널리티 필드만 Loki label로 승격한다")
    void alloy_promotesOnlyLowCardinalityLabels() throws IOException {
        String alloy = read("alloy/config.alloy");
        String labelsStage = alloy.substring(
                alloy.indexOf("stage.labels"),
                alloy.indexOf("forward_to", alloy.indexOf("stage.labels")));

        assertThat(labelsStage)
                .contains("environment = \"\"")
                .contains("level       = \"\"")
                .contains("service     = \"\"")
                .doesNotContain("request_id")
                .doesNotContain("logger_name");
    }

    @Test
    @DisplayName("Alloy는 독립 관찰 서버의 Loki에만 로그를 전송한다")
    void alloy_forwardsLogsOnlyToRemoteLoki() throws IOException {
        String alloy = read("alloy/config.alloy");
        String compose = read("docker-compose.telemetry.yml");

        assertThat(alloy)
                .contains("loki.write.remote.receiver")
                .contains("url = sys.env(\"LOKI_REMOTE_WRITE_URL\")")
                .doesNotContain("loki.write.local")
                .doesNotContain("http://loki:3100");
        assertThat(compose)
                .contains("LOKI_REMOTE_WRITE_URL: ${LOKI_REMOTE_WRITE_URL:?LOKI_REMOTE_WRITE_URL is required}")
                .doesNotContain("\n  incident-api:")
                .doesNotContain("\n  incident-worker:")
                .doesNotContain("\n  grafana:")
                .doesNotContain("\n  prometheus:");
    }

    @Test
    @DisplayName("MySQL 관찰은 락 근거만 Loki로 보내고 쿼리 샘플 수집을 비활성화한다")
    void mysqlObservability_collectsLocksWithoutQuerySamples() throws IOException {
        String alloy = read("alloy/config.alloy");

        assertThat(alloy)
                .contains("database_observability.mysql \"membershipflow\"")
                .contains("enable_collectors = [\"locks\"]")
                .contains("\"query_samples\"")
                .contains("threshold = \"0s\"")
                .contains("service     = \"MembershipFlow-MySQL\"")
                .doesNotContain("disable_query_redaction = true");
    }

    @Test
    @DisplayName("운영 메트릭 포트는 지정한 사설 주소에만 바인딩한다")
    void compose_bindsMetricsPortsToConfiguredAddress() throws IOException {
        String compose = read("docker-compose.yml");
        String environmentExample = read(".env.example");

        assertThat(compose)
                .contains("${OBSERVABILITY_BIND_ADDRESS:-127.0.0.1}:8081:8081")
                .contains("${OBSERVABILITY_BIND_ADDRESS:-127.0.0.1}:9100:9100");
        String telemetryCompose = read("docker-compose.telemetry.yml");
        assertThat(telemetryCompose)
                .contains("${OBSERVABILITY_BIND_ADDRESS:-127.0.0.1}:9104:9104")
                .contains("--exporter.lock_wait_timeout=2");
        assertThat(environmentExample)
                .contains("OBSERVABILITY_BIND_ADDRESS=127.0.0.1")
                .contains("MYSQL_MONITORING_USERNAME=membershipflow_monitor")
                .doesNotContain("OBSERVABILITY_BIND_ADDRESS=0.0.0.0");
    }

    @Test
    @DisplayName("MySQL 관찰 계정은 애플리케이션 테이블 권한 없이 생성한다")
    void mysqlObservabilityUser_hasOnlyMonitoringGrants() throws IOException {
        String bootstrap = read("alloy/bootstrap-mysql-monitoring.sh");

        assertThat(bootstrap)
                .contains("GRANT PROCESS, REPLICATION CLIENT ON *.*")
                .contains("GRANT SELECT ON performance_schema.*")
                .contains("MAX_USER_CONNECTIONS 3")
                .doesNotContain("GRANT SELECT ON membershipflow.*")
                .doesNotContain("GRANT ALL");
    }

    @Test
    @DisplayName("애플리케이션 배포에는 관찰 제어면 서비스를 포함하지 않는다")
    void deployment_excludesLegacyObservabilityControlPlane() throws IOException {
        String compose = read("docker-compose.yml");
        String deployment = read(".github/workflows/cd-pipeline.yml");

        assertThat(compose)
                .doesNotContain("\n  prometheus:")
                .doesNotContain("\n  grafana:")
                .doesNotContain("\n  loki:")
                .doesNotContain("\n  incident-api:")
                .doesNotContain("\n  incident-worker:");
        assertThat(deployment)
                .doesNotContain("membershipflow-incident-analyzer")
                .doesNotContain("incident-migrate")
                .doesNotContain("wait-for-incident-health")
                .contains("docker-compose.telemetry.yml")
                .contains("Stopping legacy observability service");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath));
    }
}
