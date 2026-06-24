package com.membershipflow.subscription.client;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class TossPaymentsClient {

    private final RestClient restClient;
    private final String secretKey;

    public TossPaymentsClient(
            @Value("${toss.api-base-url}") String baseUrl,
            @Value("${toss.secret-key}") String secretKey) {
        this.secretKey  = secretKey;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /** 빌링 키 발급 */
    public BillingKeyResponse issueBillingKey(String customerKey, String authKey) {
        try {
            return restClient.post()
                    .uri("/v1/billing/authorizations/issue")
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("customerKey", customerKey, "authKey", authKey))
                    .retrieve()
                    .body(BillingKeyResponse.class);
        } catch (RestClientException e) {
            log.error("빌링 키 발급 실패: customerKey={}", customerKey, e);
            throw new BusinessException(ErrorCode.BILLING_KEY_ISSUE_FAILED);
        }
    }

    /** 자동결제 승인 */
    public PaymentResponse charge(String billingKey, String customerKey,
                                   int amount, String orderId, String orderName) {
        try {
            return restClient.post()
                    .uri("/v1/billing/{billingKey}", billingKey)
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "customerKey", customerKey,
                            "amount",      amount,
                            "orderId",     orderId,
                            "orderName",   orderName))
                    .retrieve()
                    .body(PaymentResponse.class);
        } catch (RestClientException e) {
            log.error("자동결제 실패: billingKey=***, orderId={}", orderId, e);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        }
    }

    private String basicAuth() {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    public record BillingKeyResponse(
            String billingKey,
            String customerKey,
            CardInfo card
    ) {
        public record CardInfo(String number, String cardCompany) {}
    }

    public record PaymentResponse(
            String paymentKey,
            String approvedAt,
            int totalAmount,
            FailureInfo failure
    ) {
        public record FailureInfo(String code, String message) {}
    }
}
