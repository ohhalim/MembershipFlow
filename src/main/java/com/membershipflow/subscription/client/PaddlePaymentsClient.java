package com.membershipflow.subscription.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.membershipflow.common.exception.BusinessException;
import com.membershipflow.common.exception.ErrorCode;
import java.time.Duration;
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

@Slf4j
@Component
public class PaddlePaymentsClient {

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
        } catch (RestClientException e) {
            log.error("Paddle 거래 생성 실패: attemptId={}", attemptId, e);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED_ERROR);
        }
    }
}
