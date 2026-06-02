package com.wallet.APIgateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Configuration
public class RateLimitConfig {
    /*
     * Resolves the rate limit key based on the client's IP address.
     * Uses Project Reactor (Mono) for non-blocking execution.
     */

    @Bean
    @Primary
    public KeyResolver ipKeyResolver(){
        return exchange -> Mono.just(
                Objects.requireNonNull(exchange.getRequest().getRemoteAddress())
                        .getAddress()
                        .getHostAddress()
        );
    }
}
