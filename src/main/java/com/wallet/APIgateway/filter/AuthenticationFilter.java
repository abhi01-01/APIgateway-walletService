package com.wallet.APIgateway.filter;

import com.wallet.APIgateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class AuthenticationFilter extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {
    private final RouteValidator validator;
    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redisTemplate;

    public AuthenticationFilter(RouteValidator validator, JwtUtil jwtUtil, ReactiveStringRedisTemplate redisTemplate) {
        super(Config.class);
        this.validator = validator;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
    }

    public static class Config {}

    @Override
    public GatewayFilter apply(Config config) {
        return ((exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (request.getMethod() == HttpMethod.OPTIONS) {
                return chain.filter(exchange);
            }

            if (validator.isSecured.test(request)) {
                if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                    log.warn("Edge Security: Missing Authorization Header");
                    return onError(exchange);
                }

                String authHeader = request.getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).getFirst();
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    authHeader = authHeader.substring(7);
                }

                final String token = authHeader;

                // 1. Reactive Pipeline: Intercept and look up token in the blacklisted keyspace
                return redisTemplate.hasKey("blacklist:" + token)
                        .flatMap(isBlacklisted -> {
                            if (isBlacklisted) {
                                log.warn("Edge Security: Rejecting blacklisted token attempt");
                                return onError(exchange);
                            }

                            // 1. Determine if this is a graceful teardown route
                            String path = request.getURI().getPath();
                            boolean isTeardownRoute = path.contains("/logout") || path.contains("/close-account");

                            try {
                                // 2. Pass the context to the Cryptographic Utility
                                io.jsonwebtoken.Claims claims = jwtUtil.extractClaims(token, isTeardownRoute);

                                String userId = claims.getSubject();
                                String role = claims.get("ownerType", String.class);

                                ServerHttpRequest mutatedRequest = exchange.getRequest()
                                        .mutate()
                                        .header("X-User-Id", userId)
                                        .header("X-User-Role", role)
                                        .build();

                                // 3. Handle Redis Blacklisting for active tokens
                                if (isTeardownRoute) {
                                    long ttlMs = jwtUtil.getRemainingExpirationMs(token);

                                    if (ttlMs > 0) {
                                        return redisTemplate.opsForValue()
                                                .set("blacklist:" + token, "revoked", Duration.ofMillis(ttlMs))
                                                .then(chain.filter(exchange.mutate().request(mutatedRequest).build()));
                                    }
                                }

                                return chain.filter(exchange.mutate().request(mutatedRequest).build());

                            } catch (Exception e) {
                                log.error("Edge Security: Token validation failed - {}", e.getMessage());
                                return onError(exchange);
                            }
                        });
            }
            return chain.filter(exchange);
        });
    }

    private Mono<Void> onError(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

}
