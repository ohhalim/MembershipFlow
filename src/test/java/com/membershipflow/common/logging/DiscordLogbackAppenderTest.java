package com.membershipflow.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DiscordLogbackAppenderTest {

    private HttpServer server;
    private final CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();

    private HttpServer startFakeWebhook() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/webhook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            receivedBodies.add(body);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        s.start();
        return s;
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private LoggingEvent errorEvent(String loggerName, String message) {
        return loggingEvent(loggerName, Level.ERROR, message);
    }

    private LoggingEvent loggingEvent(String loggerName, Level level, String message) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(logger.getName());
        event.setLevel(level);
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }

    @Test
    @DisplayName("웹훅 URL이 비어있으면 아무것도 전송하지 않는다")
    void append_blankWebhookUrl_doesNothing() throws IOException {
        server = startFakeWebhook();
        DiscordLogbackAppender appender = new DiscordLogbackAppender();
        appender.setWebhookUrl("");
        appender.start();

        appender.doAppend(errorEvent("test.Logger", "터진 에러"));

        // 잠깐 기다려도 요청이 오지 않아야 함
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    @DisplayName("ERROR 레벨 로그를 웹훅으로 전송한다")
    void append_errorLevel_sendsToWebhook() throws IOException {
        server = startFakeWebhook();
        DiscordLogbackAppender appender = new DiscordLogbackAppender();
        appender.setWebhookUrl("http://localhost:" + server.getAddress().getPort() + "/webhook");
        appender.start();

        appender.doAppend(errorEvent("com.membershipflow.TestLogger", "결제 실패"));

        await().atMost(3, TimeUnit.SECONDS).until(() -> !receivedBodies.isEmpty());
        assertThat(receivedBodies.get(0)).contains("결제 실패");
        assertThat(receivedBodies.get(0)).contains("com.membershipflow.TestLogger");
    }

    @Test
    @DisplayName("INFO 레벨은 전송하지 않는다")
    void append_infoLevel_doesNotSend() throws IOException {
        server = startFakeWebhook();
        DiscordLogbackAppender appender = new DiscordLogbackAppender();
        appender.setWebhookUrl("http://localhost:" + server.getAddress().getPort() + "/webhook");
        appender.start();

        LoggingEvent info = loggingEvent("test.Logger", Level.INFO, "정보성 로그");
        appender.doAppend(info);

        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    @DisplayName("같은 로거+메시지는 5분 내 재전송하지 않는다")
    void append_duplicateWithinWindow_sendsOnce() throws IOException {
        server = startFakeWebhook();
        DiscordLogbackAppender appender = new DiscordLogbackAppender();
        appender.setWebhookUrl("http://localhost:" + server.getAddress().getPort() + "/webhook");
        appender.start();

        appender.doAppend(errorEvent("com.membershipflow.Dup", "반복 에러"));
        appender.doAppend(errorEvent("com.membershipflow.Dup", "반복 에러"));
        appender.doAppend(errorEvent("com.membershipflow.Dup", "반복 에러"));

        await().atMost(3, TimeUnit.SECONDS).until(() -> !receivedBodies.isEmpty());
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {
        }
        assertThat(receivedBodies).hasSize(1);
    }
}
