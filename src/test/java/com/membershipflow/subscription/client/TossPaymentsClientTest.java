package com.membershipflow.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossPaymentsClientTest {

    private MockRestServiceServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(
                builder.baseUrl("https://api.tosspayments.com").build(),
                "test-secret-key");
    }

    @Test
    void issueBillingKey_sendsStoredIdempotencyKey() {
        server.expect(requestTo(
                        "https://api.tosspayments.com/v1/billing/authorizations/issue"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
                .andExpect(header("Idempotency-Key", "issue-idempotency-key"))
                .andExpect(content().json("""
                        {"customerKey":"customer-key","authKey":"auth-key"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "billingKey":"billing-key",
                          "customerKey":"customer-key",
                          "cardCompany":"국민",
                          "cardNumber":"1234-****-****-5678",
                          "card":{"number":"1234-****-****-5678"}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.issueBillingKey(
                "customer-key", "auth-key", "issue-idempotency-key");

        assertThat(response.billingKey()).isEqualTo("billing-key");
        assertThat(response.cardCompany()).isEqualTo("국민");
        assertThat(response.cardNumber()).isEqualTo("1234-****-****-5678");
        server.verify();
    }

    @Test
    void charge_sendsStoredIdempotencyKeyAndMapsCompletionFields() {
        server.expect(requestTo(
                        "https://api.tosspayments.com/v1/billing/billing-key"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basicAuth()))
                .andExpect(header("Idempotency-Key", "charge-idempotency-key"))
                .andExpect(content().json("""
                        {
                          "customerKey":"customer-key",
                          "amount":9900,
                          "orderId":"ORDER-customer-key",
                          "orderName":"프리미엄 구독 결제"
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "paymentKey":"payment-key",
                          "orderId":"ORDER-customer-key",
                          "status":"DONE",
                          "type":"BILLING",
                          "approvedAt":"2026-08-04T12:00:00+09:00",
                          "totalAmount":9900
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = client.charge(
                "billing-key", "customer-key", 9900,
                "ORDER-customer-key", "프리미엄 구독 결제",
                "charge-idempotency-key");

        assertThat(response.orderId()).isEqualTo("ORDER-customer-key");
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.type()).isEqualTo("BILLING");
        server.verify();
    }

    @Test
    void constructor_rejectsReadTimeoutShorterThanTossMinimum() {
        assertThatThrownBy(() -> new TossPaymentsClient(
                RestClient.builder(),
                "https://api.tosspayments.com",
                "test-secret-key",
                Duration.ofSeconds(5),
                Duration.ofSeconds(59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 60 seconds");
    }

    @Test
    void constructor_rejectsNonPositiveConnectTimeout() {
        assertThatThrownBy(() -> new TossPaymentsClient(
                RestClient.builder(),
                "https://api.tosspayments.com",
                "test-secret-key",
                Duration.ZERO,
                Duration.ofSeconds(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect timeout must be positive");
    }

    private String basicAuth() {
        String credentials = "test-secret-key:";
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
