package com.membershipflow.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class PaddleTransactionCircuitConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(CircuitBreakerAutoConfiguration.class));

    @Test
    void applicationConfigBindsPaddleTransactionCircuit() {
        contextRunner.run(context -> {
            CircuitBreakerConfig config = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker(PaddlePaymentsClient.TRANSACTION_CIRCUIT)
                    .getCircuitBreakerConfig();

            assertThat(config.getSlidingWindowType())
                    .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
            assertThat(config.getSlidingWindowSize()).isEqualTo(60);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
            assertThat(config.getSlowCallRateThreshold()).isEqualTo(50f);
            assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
            assertThat(config.getRecordExceptionPredicate().test(
                    HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "", null, null, null)))
                    .isFalse();
            assertThat(config.getRecordExceptionPredicate().test(
                    HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "", null, null, null)))
                    .isTrue();
        });
    }
}
