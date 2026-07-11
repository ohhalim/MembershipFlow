package com.membershipflow.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ERROR 레벨 로그를 Discord 웹훅으로 비동기 전송한다.
 *
 * <p>Logback은 Spring 컨텍스트보다 먼저 초기화되므로 이 클래스는 Spring 빈이 아니라
 * 순수 Logback Appender다. 웹훅 URL은 {@code logback-spring.xml}의
 * {@code <springProperty>}로 Spring 설정(discord.webhook.error-url)에서 값을 받아
 * setter로 주입받는다. URL이 비어있으면(로컬/CI) 완전히 no-op이다.
 *
 * <p>같은 로거+메시지 조합은 5분 내 재전송하지 않아 장애 폭주 시 채널 스팸을 막는다.
 */
public class DiscordLogbackAppender extends AppenderBase<ILoggingEvent> {

    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_CONTENT_LENGTH = 1800;

    private String webhookUrl;

    private HttpClient httpClient;
    private ExecutorService executor;
    private final Map<String, Instant> lastSentAt = new ConcurrentHashMap<>();

    /** logback-spring.xml의 <webhookUrl> 파라미터로 주입된다. */
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void start() {
        super.start();
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "discord-alert-appender");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (webhookUrl == null || webhookUrl.isBlank() || httpClient == null) {
            return;
        }
        if (event.getLevel().toInt() < ch.qos.logback.classic.Level.ERROR_INT) {
            return;
        }
        if (isDuplicateWithinWindow(event)) {
            return;
        }
        String payload = buildPayload(event);
        executor.submit(() -> send(payload));
    }

    private boolean isDuplicateWithinWindow(ILoggingEvent event) {
        String key = event.getLoggerName() + "|" + event.getFormattedMessage();
        Instant now = Instant.now();
        Instant previous = lastSentAt.put(key, now);
        return previous != null && Duration.between(previous, now).compareTo(DEDUP_WINDOW) < 0;
    }

    private String buildPayload(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        StringBuilder content = new StringBuilder()
                .append("🚨 **[").append(event.getLevel()).append("] MembershipFlow**\n")
                .append("logger: `").append(event.getLoggerName()).append("`\n")
                .append("```").append(message);

        if (event.getThrowableProxy() != null) {
            content.append('\n').append(event.getThrowableProxy().getClassName())
                    .append(": ").append(event.getThrowableProxy().getMessage());
        }
        content.append("```");

        String truncated = content.length() > MAX_CONTENT_LENGTH
                ? content.substring(0, MAX_CONTENT_LENGTH) + "...(truncated)```"
                : content.toString();

        return "{\"content\":" + jsonString(truncated) + "}";
    }

    private String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private void send(String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            addError("Discord 웹훅 전송 실패: " + e.getMessage());
        }
    }
}
