package com.membershipflow.collect.collector;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 동아골프 서버 전용 HTTP 클라이언트 (#435).
 *
 * <p>2026-09-17부터 동아골프 서버는 TLS 1.2의 RSA 키 교환 암호군만 받는다. Java 기본 보안 정책은
 * TLS_RSA_* 를 막고 있어 핸드셰이크가 실패한다. 이 정책은 JVM 전체에 걸려 있어 연결 하나만 풀 수 없고,
 * 전역으로 풀면 결제를 포함한 모든 외부 연결이 영향을 받는다. 그래서 동아골프 요청만 curl 별도 프로세스로 보낸다.
 *
 * <ul>
 *   <li>인증서 검증은 유지하고 암호군은 고정하지 않는다. 서버가 ECDHE를 다시 켜면 curl이 그쪽을 고른다.</li>
 *   <li>서버가 응답 끝(&lt;/html&gt; 뒤 주석)에서 연결을 끊는 경우가 잦다(curl 종료 코드 56).
 *       상태가 200이고 HTML이 끝까지 왔으면 받은 본문을 쓴다.</li>
 * </ul>
 */
@Slf4j
@Component
public class DongaHttpClient {

    static final String BASE_URL = "https://www.dongagolf.co.kr/";
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; MembershipFlowBot/1.0)";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_TIME_SECONDS = 20;
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final int CURL_RECV_ERROR = 56;
    private static final Pattern STATUS = Pattern.compile("MF_HTTP_STATUS=(\\d{3})");

    private final String curl;
    private final Duration processTimeout;

    public DongaHttpClient() {
        this("curl", Duration.ofSeconds(MAX_TIME_SECONDS + 5));
    }

    DongaHttpClient(String curl, Duration processTimeout) {
        this.curl = curl;
        this.processTimeout = processTimeout;
    }

    /** HTML 페이지를 받아 Jsoup 문서로 만든다. 문자셋은 페이지의 meta 선언을 따른다. */
    public Document getDocument(String url) throws IOException {
        Response response = get(url);
        if (response.cutAtEnd() && !response.containsHtmlEnd()) {
            throw new IOException("동아골프 응답이 중간에 끊김: " + url);
        }
        return Jsoup.parse(new ByteArrayInputStream(response.body()), null, url);
    }

    /** JSON 같은 텍스트 응답을 UTF-8 문자열로 받는다. */
    public String getText(String url) throws IOException {
        return new String(get(url).body(), StandardCharsets.UTF_8);
    }

    Response get(String url) throws IOException {
        if (!url.startsWith(BASE_URL)) {
            throw new IllegalArgumentException("동아골프 주소만 요청할 수 있습니다: " + url);
        }

        Process process = new ProcessBuilder(command(url)).start();
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = readers.submit(() -> readBody(process));
            Future<byte[]> stderr = readers.submit(() -> process.getErrorStream().readAllBytes());

            if (!process.waitFor(processTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("동아골프 요청 시간 초과: " + url);
            }
            return toResponse(url, process.exitValue(), stdout.get(),
                    new String(stderr.get(), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("동아골프 요청 중단: " + url, e);
        } catch (ExecutionException e) {
            process.destroyForcibly();
            throw new IOException("동아골프 응답 읽기 실패: " + url, e.getCause());
        }
    }

    List<String> command(String url) {
        return List.of(curl,
                "--silent", "--show-error",
                "--location", "--max-redirs", "3",
                "--proto", "=https", "--proto-redir", "=https",
                "--connect-timeout", String.valueOf(CONNECT_TIMEOUT_SECONDS),
                "--max-time", String.valueOf(MAX_TIME_SECONDS),
                "--user-agent", USER_AGENT,
                "--write-out", "%{stderr}MF_HTTP_STATUS=%{http_code}\\n",
                url);
    }

    private static byte[] readBody(Process process) throws IOException {
        InputStream in = process.getInputStream();
        byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            process.destroyForcibly();
            throw new IOException("동아골프 응답이 너무 큽니다 (" + MAX_BODY_BYTES + "바이트 초과)");
        }
        return body;
    }

    private Response toResponse(String url, int exitCode, byte[] body, String stderr) throws IOException {
        int status = parseStatus(stderr);
        boolean cutAtEnd = exitCode == CURL_RECV_ERROR && body.length > 0;
        if (status == 200 && (exitCode == 0 || cutAtEnd)) {
            if (cutAtEnd) {
                log.debug("[동아골프] 응답 끝에서 연결이 끊겨 받은 본문 사용: {}", url);
            }
            return new Response(body, cutAtEnd);
        }
        throw new IOException("동아골프 요청 실패: exit=" + exitCode + ", status=" + status
                + ", " + firstLine(stderr) + " (" + url + ")");
    }

    private static int parseStatus(String stderr) {
        Matcher m = STATUS.matcher(stderr);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String firstLine(String stderr) {
        return stderr.lines()
                .filter(line -> !line.startsWith("MF_HTTP_STATUS="))
                .findFirst()
                .orElse("");
    }

    record Response(byte[] body, boolean cutAtEnd) {
        boolean containsHtmlEnd() {
            return new String(body, StandardCharsets.UTF_8).toLowerCase().contains("</html>");
        }
    }
}
