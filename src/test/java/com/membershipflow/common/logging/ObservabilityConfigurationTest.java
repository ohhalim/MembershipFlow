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
    @DisplayName("Alloy는 전환 기간 동안 로컬 Loki와 원격 Loki에 로그를 함께 전송한다")
    void alloy_forwardsLogsToLocalAndRemoteLoki() throws IOException {
        String alloy = read("alloy/config.alloy");
        String compose = read("docker-compose.incident.yml");

        assertThat(alloy)
                .contains("loki.write.local.receiver")
                .contains("loki.write.remote.receiver")
                .contains("url = sys.env(\"LOKI_REMOTE_WRITE_URL\")");
        assertThat(compose)
                .contains("LOKI_REMOTE_WRITE_URL: ${LOKI_REMOTE_WRITE_URL:-http://loki:3100/loki/api/v1/push}");
    }

    @Test
    @DisplayName("운영 메트릭 포트는 지정한 사설 주소에만 바인딩한다")
    void compose_bindsMetricsPortsToConfiguredAddress() throws IOException {
        String compose = read("docker-compose.yml");
        String environmentExample = read(".env.example");

        assertThat(compose)
                .contains("${OBSERVABILITY_BIND_ADDRESS:-127.0.0.1}:8081:8081")
                .contains("${OBSERVABILITY_BIND_ADDRESS:-127.0.0.1}:9100:9100");
        assertThat(environmentExample)
                .contains("OBSERVABILITY_BIND_ADDRESS=127.0.0.1")
                .doesNotContain("OBSERVABILITY_BIND_ADDRESS=0.0.0.0");
    }

    @Test
    @DisplayName("로컬 관측 구성은 Docker socket 없이 loopback 포트만 노출한다")
    void compose_usesLoopbackPortsWithoutDockerSocket() throws IOException {
        String compose = read("docker-compose.observability.yml");

        assertThat(compose)
                .contains("127.0.0.1:8081:8081")
                .contains("127.0.0.1:3100:3100")
                .contains("127.0.0.1:12345:12345")
                .doesNotContain("/var/run/docker.sock");
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(PROJECT_ROOT.resolve(relativePath));
    }
}
