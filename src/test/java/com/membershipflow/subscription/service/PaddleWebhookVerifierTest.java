package com.membershipflow.subscription.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PaddleWebhookVerifierTest {

    private static final String SECRET = "pdl_ntfset_test_secret";
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @Test
    void verify_acceptsSignatureForExactRawBody() throws Exception {
        String body = "{\"event_id\":\"evt_test\"}";
        long timestamp = NOW.getEpochSecond();
        PaddleWebhookVerifier verifier = verifier();

        assertThatCode(() -> verifier.verify(
                body, "ts=" + timestamp + ";h1=" + sign(timestamp, body)))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_rejectsTransformedBody() throws Exception {
        String body = "{\"event_id\":\"evt_test\"}";
        long timestamp = NOW.getEpochSecond();
        PaddleWebhookVerifier verifier = verifier();

        assertThatThrownBy(() -> verifier.verify(
                body + " ", "ts=" + timestamp + ";h1=" + sign(timestamp, body)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
    }

    @Test
    void verify_rejectsExpiredSignature() throws Exception {
        String body = "{}";
        long timestamp = NOW.minus(Duration.ofMinutes(6)).getEpochSecond();

        assertThatThrownBy(() -> verifier().verify(
                body, "ts=" + timestamp + ";h1=" + sign(timestamp, body)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
    }

    private PaddleWebhookVerifier verifier() {
        return new PaddleWebhookVerifier(
                SECRET, Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String sign(long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(
                (timestamp + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }
}
