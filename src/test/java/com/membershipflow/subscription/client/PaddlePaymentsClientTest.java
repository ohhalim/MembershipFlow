package com.membershipflow.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.membershipflow.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PaddlePaymentsClientTest {

    private MockRestServiceServer server;
    private PaddlePaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PaddlePaymentsClient(
                builder.baseUrl("https://sandbox-api.paddle.com").build());
    }

    @Test
    void createTransaction_usesServerOwnedCheckoutMetadata() {
        server.expect(requestTo("https://sandbox-api.paddle.com/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "items":[{"price_id":"pri_monthly","quantity":1}],
                          "collection_mode":"automatic",
                          "custom_data":{
                            "checkout_attempt_id":"attempt-id",
                            "member_id":10,
                            "plan_id":20
                          }
                        }
                        """))
                .andRespond(withSuccess("""
                        {"data":{"id":"txn_01m0testtransaction000000000"}}
                        """, MediaType.APPLICATION_JSON));

        String transactionId = client.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L);

        assertThat(transactionId).isEqualTo("txn_01m0testtransaction000000000");
        server.verify();
    }

    @Test
    void createTransaction_classifiesClientRejectionAsRetrySafeFailure() {
        server.expect(requestTo("https://sandbox-api.paddle.com/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L))
                .isInstanceOf(PaddleTransactionRejectedException.class);
        server.verify();
    }

    @Test
    void createTransaction_keepsServerFailureAsUncertainResult() {
        server.expect(requestTo("https://sandbox-api.paddle.com/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServiceUnavailable());

        assertThatThrownBy(() -> client.createTransaction(
                "pri_monthly", "attempt-id", 10L, 20L))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(PaddleTransactionRejectedException.class);
        server.verify();
    }

    @Test
    void cancelSubscription_schedulesCancellationAtNextBillingPeriod() {
        server.expect(requestTo("https://sandbox-api.paddle.com/subscriptions/sub_test/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"effective_from":"next_billing_period"}
                        """))
                .andRespond(withSuccess("""
                        {
                          "data":{
                            "id":"sub_test",
                            "status":"active",
                            "scheduled_change":{
                              "action":"cancel",
                              "effective_at":"2026-09-24T01:00:00Z"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        PaddlePaymentsClient.CancellationResult result =
                client.cancelSubscription("sub_test");

        assertThat(result.effectiveAt()).isNotNull();
        server.verify();
    }
}
