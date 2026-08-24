package com.membershipflow.subscription.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
public class PaddlePaymentsClient {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final RestClient restClient;

    @Autowired
    public PaddlePaymentsClient(
            RestClient.Builder builder,
            @Value("${paddle.api-base-url}") String baseUrl,
            @Value("${paddle.api-key}") String apiKey,
            @Value("${paddle.connect-timeout}") Duration connectTimeout,
            @Value("${paddle.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    PaddlePaymentsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public String createTransaction(String priceId, String attemptId,
                                    Long memberId, Long planId) {
        try {
            JsonNode response = restClient.post()
                    .uri("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "items", List.of(Map.of("price_id", priceId, "quantity", 1)),
                            "collection_mode", "automatic",
                            "custom_data", Map.of(
                                    "checkout_attempt_id", attemptId,
                                    "member_id", memberId,
                                    "plan_id", planId)))
                    .retrieve()
                    .body(JsonNode.class);
            String transactionId = response == null ? null : response.path("data").path("id").asText(null);
            if (transactionId == null || !transactionId.startsWith("txn_")) {
                throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
            }
            return transactionId;
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn("Paddle 거래 생성 거절: attemptId={}, status={}",
                        attemptId, e.getStatusCode().value());
                throw new PaddleTransactionRejectedException(e);
            }
            log.error("Paddle 거래 생성 응답 실패: attemptId={}, status={}",
                    attemptId, e.getStatusCode().value(), e);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        } catch (RestClientException e) {
            log.error("Paddle 거래 생성 실패: attemptId={}", attemptId, e);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        }
    }

    public CancellationResult cancelSubscription(String subscriptionId) {
        try {
            JsonNode response = restClient.post()
                    .uri("/subscriptions/{subscriptionId}/cancel", subscriptionId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("effective_from", "next_billing_period"))
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = response == null ? null : response.path("data");
            JsonNode scheduledChange = data == null ? null : data.path("scheduled_change");
            String returnedId = data == null ? null : data.path("id").asText(null);
            String action = scheduledChange == null ? null
                    : scheduledChange.path("action").asText(null);
            String effectiveAt = scheduledChange == null ? null
                    : scheduledChange.path("effective_at").asText(null);
            if (!subscriptionId.equals(returnedId)
                    || !"cancel".equals(action)
                    || effectiveAt == null) {
                throw new BusinessException(ErrorCode.PAYMENT_STATUS_CHECK_FAILED);
            }
            return new CancellationResult(
                    OffsetDateTime.parse(effectiveAt)
                            .atZoneSameInstant(SERVICE_ZONE)
                            .toLocalDateTime());
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException | java.time.format.DateTimeParseException e) {
            log.error("Paddle 구독 해지 예약 실패: subscriptionId={}", subscriptionId, e);
            throw new BusinessException(ErrorCode.PAYMENT_STATUS_CHECK_FAILED);
        }
    }

    public record CancellationResult(LocalDateTime effectiveAt) {}
}
