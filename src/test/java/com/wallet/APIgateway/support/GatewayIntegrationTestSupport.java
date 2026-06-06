package com.wallet.APIgateway.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GatewayIntegrationTestSupport {

    public static final String TEST_GATEWAY_SECRET = "test-gateway-secret";
    public static final String TEST_JWT_SECRET = Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    private GatewayIntegrationTestSupport() {
    }

    public static MockWebServer startWalletService() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start mock wallet service", e);
        }
    }

    public static void registerCommonProperties(DynamicPropertyRegistry registry,
                                                GenericContainer<?> redis,
                                                MockWebServer walletService) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("application.security.jwt.secret-key", () -> TEST_JWT_SECRET);
        registry.add("application.rate-limit.trusted-proxies[0]", () -> "127.0.0.1/32");
        registry.add("application.rate-limit.trusted-proxies[1]", () -> "::1/128");
        registry.add("WALLET_SERVICE_URL", () -> walletService.url("/").toString());
        registry.add("GATEWAY_INTERNAL_SECRET", () -> TEST_GATEWAY_SECRET);
    }

    public static String createToken(String subject, String ownerType, Duration validFor) {
        SecretKey signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_JWT_SECRET));
        Date expiration = Date.from(Instant.now().plus(validFor));

        return Jwts.builder()
                .subject(subject)
                .claim("ownerType", ownerType)
                .expiration(expiration)
                .signWith(signingKey)
                .compact();
    }

    public static void clearRedis(ReactiveStringRedisTemplate redisTemplate) {
        List<String> keys = redisTemplate.keys("*").collectList().block();
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys.toArray(String[]::new)).block();
        }
    }

    public static void drainRecordedRequests(MockWebServer walletService) {
        try {
            while (walletService.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
                // Drain the queue so each test starts from a clean slate.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while draining recorded requests", e);
        }
    }
}
