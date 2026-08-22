package com.membershipflow.subscription.client;

import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class TossPaymentsClient {

    private final RestClient restClient;
    private final String secretKey;

    public TossPaymentsClient(
            RestClient.Builder restClientBuilder,
            @Value("${toss.api-base-url}") String baseUrl,
            @Value("${toss.secret-key}") String secretKey,
            @Value("${toss.connect-timeout}") Duration connectTimeout,
            @Value("${toss.read-timeout}") Duration readTimeout) {
        validateTimeouts(connectTimeout, readTimeout);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.secretKey  = secretKey;
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    TossPaymentsClient(RestClient restClient, String secretKey) {
        this.restClient = restClient;
        this.secretKey = secretKey;
    }

    private static void validateTimeouts(Duration connectTimeout, Duration readTimeout) {
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Toss connect timeout must be positive");
        }
        if (readTimeout.compareTo(Duration.ofSeconds(60)) < 0) {
            throw new IllegalArgumentException("Toss read timeout must be at least 60 seconds");
        }
    }

    /** 빌링 키 발급 */
    public BillingKeyResponse issueBillingKey(String customerKey, String authKey,
                                              String idempotencyKey) {
        try {
            return restClient.post()
                    .uri("/v1/billing/authorizations/issue")
                    .header("Authorization", basicAuth())
                    .header("Idempotency-Key", idempotencyKey)
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
        return charge(billingKey, customerKey, amount, orderId, orderName, null);
    }

    /** 자동결제 승인. idempotencyKey가 있으면 응답 유실 후에도 같은 요청을 안전하게 재호출한다. */
    public PaymentResponse charge(String billingKey, String customerKey,
                                  int amount, String orderId, String orderName,
                                  String idempotencyKey) {
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/v1/billing/{billingKey}", billingKey)
                    .header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON);
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            return request.body(Map.of(
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

    /** 주문번호로 Toss 승인 결과를 조회한다. 404는 승인 내역 없음으로 처리한다. */
    public Optional<PaymentResponse> findPaymentByOrderId(String orderId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/v1/payments/orders/{orderId}", orderId)
                    .header("Authorization", basicAuth())
                    .retrieve()
                    .body(PaymentResponse.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("결제 승인 상태 조회 실패: orderId={}", orderId, e);
            throw new BusinessException(ErrorCode.PAYMENT_STATUS_CHECK_FAILED);
        }
    }

    private String basicAuth() {
        String credentials = secretKey + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    public record BillingKeyResponse(
            String billingKey,
            String customerKey,
            CardInfo card,
            String cardCompany,
            String cardNumber
    ) {
        public record CardInfo(String number) {}
    }

    public record PaymentResponse(
            String paymentKey,
            String orderId,
            String status,
            String type,
            String approvedAt,
            int totalAmount,
            FailureInfo failure
    ) {
        public PaymentResponse(String paymentKey, String approvedAt,
                               int totalAmount, FailureInfo failure) {
            this(paymentKey, null, null, null, approvedAt, totalAmount, failure);
        }

        public record FailureInfo(String code, String message) {}
    }
}
