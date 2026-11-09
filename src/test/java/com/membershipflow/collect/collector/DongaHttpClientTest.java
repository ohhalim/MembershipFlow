package com.membershipflow.collect.collector;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * curl 대신 같은 출력을 흉내 내는 셸 스크립트로 성공, 실패, 시간 초과 경로를 확인한다.
 * 실제 curl은 본문을 stdout으로, 오류와 --write-out의 상태 코드를 stderr로 내보낸다.
 */
class DongaHttpClientTest {

    private static final String URL = DongaCourseLinkFetcher.LISTING_URL;

    @TempDir
    Path dir;

    private DongaHttpClient clientRunning(String script) throws IOException {
        Path fakeCurl = dir.resolve("fake-curl");
        Files.writeString(fakeCurl, "#!/bin/sh\n" + script + "\n");
        assertThat(fakeCurl.toFile().setExecutable(true)).isTrue();
        return new DongaHttpClient(fakeCurl.toString(), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("상태 200으로 끝나면 HTML 문서를 돌려준다")
    void getDocument_success_returnsDocument() throws IOException {
        // given
        DongaHttpClient client = clientRunning("""
                printf '<html><body><table class="list_even"><tr><td>가야</td></tr></table></body></html>'
                echo 'MF_HTTP_STATUS=200' >&2
                exit 0""");

        // when
        Document doc = client.getDocument(URL);

        // then
        assertThat(doc.select("table.list_even td").text()).isEqualTo("가야");
    }

    @Test
    @DisplayName("응답 끝에서 연결이 끊겨도 HTML이 끝까지 왔으면 받은 본문을 쓴다")
    void getDocument_connectionResetAfterHtmlEnd_usesReceivedBody() throws IOException {
        // given: 동아골프 서버는 </html> 뒤 주석을 보내다 연결을 끊는 경우가 잦다 (curl 종료 코드 56)
        DongaHttpClient client = clientRunning("""
                printf '<html><body><table class="list_even"></table></body></html>'
                echo 'curl: (56) Recv failure: Connection reset by peer' >&2
                echo 'MF_HTTP_STATUS=200' >&2
                exit 56""");

        // when
        Document doc = client.getDocument(URL);

        // then
        assertThat(doc.select("table.list_even")).hasSize(1);
    }

    @Test
    @DisplayName("HTML 중간에서 연결이 끊기면 실패로 처리한다")
    void getDocument_connectionResetBeforeHtmlEnd_throws() throws IOException {
        // given
        DongaHttpClient client = clientRunning("""
                printf '<html><body><table class="list_even"><tr>'
                echo 'curl: (56) Recv failure: Connection reset by peer' >&2
                echo 'MF_HTTP_STATUS=200' >&2
                exit 56""");

        // when & then
        assertThatThrownBy(() -> client.getDocument(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("중간에 끊김");
    }

    @Test
    @DisplayName("TLS 협상 실패처럼 curl이 실패하면 종료 코드와 오류를 담아 예외를 던진다")
    void get_curlFailure_throwsWithExitCode() throws IOException {
        // given
        DongaHttpClient client = clientRunning("""
                echo 'curl: (35) TLS connect error: sslv3 alert handshake failure' >&2
                echo 'MF_HTTP_STATUS=000' >&2
                exit 35""");

        // when & then
        assertThatThrownBy(() -> client.getDocument(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exit=35")
                .hasMessageContaining("handshake failure");
    }

    @Test
    @DisplayName("HTTP 오류 상태면 본문이 있어도 실패로 처리한다")
    void get_httpErrorStatus_throws() throws IOException {
        // given
        DongaHttpClient client = clientRunning("""
                printf '<html><body>error</body></html>'
                echo 'MF_HTTP_STATUS=500' >&2
                exit 0""");

        // when & then
        assertThatThrownBy(() -> client.getText(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("status=500");
    }

    @Test
    @DisplayName("제한 시간을 넘기면 프로세스를 끝내고 실패로 처리한다")
    void get_timeout_killsProcessAndThrows() throws IOException {
        // given
        DongaHttpClient client = clientRunning("exec sleep 10");
        long start = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> client.getText(URL))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("시간 초과");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("동아골프가 아닌 주소는 요청하지 않는다")
    void get_otherHost_rejected() {
        // given
        DongaHttpClient client = new DongaHttpClient();

        // when & then
        assertThatThrownBy(() -> client.getText("https://api.tosspayments.com/v1/payments"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("인증서 검증을 끄지 않고 https만 허용하며 암호군을 고정하지 않는다")
    void command_keepsCertificateVerification() {
        // when
        List<String> command = new DongaHttpClient().command(URL);

        // then
        assertThat(command).doesNotContain("--insecure", "-k", "--ciphers");
        assertThat(command).containsSequence("--proto", "=https");
        assertThat(command).containsSequence("--proto-redir", "=https");
        assertThat(command).contains("--max-time");
    }
}
