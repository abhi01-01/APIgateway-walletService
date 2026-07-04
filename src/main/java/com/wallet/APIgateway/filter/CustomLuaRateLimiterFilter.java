package com.wallet.APIgateway.filter;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class CustomLuaRateLimiterFilter extends AbstractGatewayFilterFactory<CustomLuaRateLimiterFilter.Config> {

    private static final String DEFAULT_KEY_PREFIX = "rate_limit:";
    private static final int DEFAULT_REQUESTED_TOKENS = 1;
    private static final String RATE_LIMIT_EXCEEDED_MESSAGE = "Try again after some time";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final ClientIpResolver clientIpResolver;

    public CustomLuaRateLimiterFilter(ReactiveStringRedisTemplate redisTemplate,
                                      ClientIpResolver clientIpResolver) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.clientIpResolver = clientIpResolver;

        // Load the script into memory on Gateway startup
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        this.script.setResultType(Long.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        boolean rateLimiterEnabled = normalizeConfig(config);

        return (exchange, chain) -> {

            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                return chain.filter(exchange);
            }
            
            if (!rateLimiterEnabled) {
                return chain.filter(exchange);
            }

            // 1. Extract IP Address as the Key
            String redisKey = config.getKeyPrefix() + clientIpResolver.resolve(exchange);
            List<String> keys = Collections.singletonList(redisKey);

            // 2. Execute Lua Script Reactively (Non-Blocking)
            return redisTemplate.execute(script, keys,
                            String.valueOf(config.getCapacity()),
                            String.valueOf(config.getReplenishRate()),
                            String.valueOf(DEFAULT_REQUESTED_TOKENS))
                    .next()
                    .switchIfEmpty(Mono.just(1L))
                    .flatMap(result -> {
                        if (result != null && result == 1L) {
                            // Tokens available, forward the request
                            return chain.filter(exchange);
                        }

                        // Rate limit exceeded, abort request at the edge
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_PLAIN);
                        byte[] responseBody = RATE_LIMIT_EXCEEDED_MESSAGE.getBytes(StandardCharsets.UTF_8);
                        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                                .bufferFactory()
                                .wrap(responseBody)));
                    })
                    .onErrorResume(e -> {
                        // Fail-open strategy: If Redis crashes, don't take down the entire API
                        log.warn("Rate limiter failed for key {}. Allowing request to continue.", redisKey, e);
                        return chain.filter(exchange);
                    });
        };
    }

    private boolean normalizeConfig(Config config) {
        if (!StringUtils.hasText(config.getKeyPrefix())) {
            config.setKeyPrefix(DEFAULT_KEY_PREFIX);
        }
        if (config.getCapacity() <= 0) {
            log.error("CustomLuaRateLimiterFilter is disabled because capacity must be greater than 0. Configured value: {}",
                    config.getCapacity());
            return false;
        }
        if (config.getReplenishRate() <= 0) {
            log.error("CustomLuaRateLimiterFilter is disabled because replenishRate must be greater than 0. Configured value: {}",
                    config.getReplenishRate());
            return false;
        }

        return true;
    }

    @Setter
    @Getter
    public static class Config {
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private int capacity;
        private double replenishRate;

    }
}
