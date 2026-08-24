package com.membershipflow.subscription.service;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaddleWebhookVerifier {

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    @Autowired
    public PaddleWebhookVerifier(
            @Value("${paddle.webhook-secret}") String secret,
            @Value("${paddle.webhook-tolerance}") Duration tolerance) {
        this(secret, tolerance, Clock.systemUTC());
    }

    PaddleWebhookVerifier(String secret, Duration tolerance, Clock clock) {
        this.secret = secret;
        this.tolerance = tolerance;
        this.clock = clock;
    }

    public void verify(String rawBody, String signatureHeader) {
        SignatureParts parts = parse(signatureHeader);
        Instant signedAt = Instant.ofEpochSecond(parts.timestamp());
        Duration age = Duration.between(signedAt, clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }

        byte[] expected = hexToBytes(hmac(parts.timestamp() + ":" + rawBody));
        boolean matches = parts.signatures().stream()
                .map(PaddleWebhookVerifier::hexToBytes)
                .anyMatch(actual -> MessageDigest.isEqual(expected, actual));
        if (!matches) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
    }

    private SignatureParts parse(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(";")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) continue;
            if ("ts".equals(pair[0])) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException ignored) {
                    throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
                }
            } else if ("h1".equals(pair[0])) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }
        return new SignatureParts(timestamp, signatures);
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return bytesToHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Paddle webhook signature verification failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return new byte[0];
        try {
            return java.util.HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private static String bytesToHex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private record SignatureParts(long timestamp, List<String> signatures) {}
}
