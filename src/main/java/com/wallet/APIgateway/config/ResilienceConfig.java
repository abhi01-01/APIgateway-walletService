package com.wallet.APIgateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    private final Duration walletServiceTimeout;
    private final int slidingWindowSize;
    private final float failureRateThreshold;
    private final Duration waitDurationInOpenState;
    private final int permittedNumberOfCallsInHalfOpenState;

    public ResilienceConfig(
            @Value("${resilience4j.timelimiter.instances.walletServiceCircuitBreaker.timeoutDuration:5s}")
            Duration walletServiceTimeout,
            @Value("${resilience4j.circuitbreaker.instances.walletServiceCircuitBreaker.slidingWindowSize:10}")
            int slidingWindowSize,
            @Value("${resilience4j.circuitbreaker.instances.walletServiceCircuitBreaker.failureRateThreshold:50}")
            float failureRateThreshold,
            @Value("${resilience4j.circuitbreaker.instances.walletServiceCircuitBreaker.waitDurationInOpenState:10s}")
            Duration waitDurationInOpenState,
            @Value("${resilience4j.circuitbreaker.instances.walletServiceCircuitBreaker.permittedNumberOfCallsInHalfOpenState:5}")
            int permittedNumberOfCallsInHalfOpenState) {
        this.walletServiceTimeout = walletServiceTimeout;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
        return factory -> factory.configure(builder -> builder
                // 1. Uses the timeout configured for the Wallet Service breaker.
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(walletServiceTimeout)
                        .build())
                // 2. Applies the configured circuit-breaker thresholds.
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(slidingWindowSize)
                        .failureRateThreshold(failureRateThreshold)
                        .waitDurationInOpenState(waitDurationInOpenState)
                        .permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
                        .build())
                .build(), "walletServiceCircuitBreaker");
    }

}
