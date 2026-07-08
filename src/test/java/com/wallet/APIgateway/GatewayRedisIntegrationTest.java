package com.wallet.APIgateway;

import com.wallet.APIgateway.support.GatewayIntegrationTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class GatewayRedisIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static final MockWebServer WALLET_SERVICE = GatewayIntegrationTestSupport.startWalletService();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        GatewayIntegrationTestSupport.registerCommonProperties(registry, REDIS, WALLET_SERVICE);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "wallet-auth-route");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].uri", () -> WALLET_SERVICE.url("/").toString());
        registry.add("spring.cloud.gateway.server.webflux.routes[0].predicates[0]", () -> "Path=/api/v1/auth/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[0].name", () -> "CustomLuaRateLimiterFilter");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[0].args.keyPrefix", () -> "rate_limit:auth:");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[0].args.capacity", () -> 1);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[0].args.replenishRate", () -> 0.001d);
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[1]", () -> "AuthenticationFilter");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[2].name", () -> "CircuitBreaker");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[2].args.name", () -> "walletServiceCircuitBreaker");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[2].args.fallbackUri", () -> "forward:/fallback/walletService");
    }

    @BeforeEach
    void resetState() {
        GatewayIntegrationTestSupport.clearRedis(redisTemplate);
        GatewayIntegrationTestSupport.drainRecordedRequests(WALLET_SERVICE);
    }

    @AfterAll
    static void shutdownWalletService() throws IOException {
        WALLET_SERVICE.shutdown();
    }

    @Test
    void customLuaRateLimiterRejectsSecondRequestAndPersistsBucketState() {
        WALLET_SERVICE.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"status\":\"first-ok\"}"));

        String forwardedIp = "198.51.100.24";

        webTestClient.get()
                .uri("/api/v1/auth/login")
                .header("X-Forwarded-For", forwardedIp)
                .exchange()
                .expectStatus().isOk();

        webTestClient.get()
                .uri("/api/v1/auth/login")
                .header("X-Forwarded-For", forwardedIp)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)
                .expectBody(String.class).isEqualTo("Try again after some time");

        List<String> redisKeys = redisTemplate.keys("rate_limit:auth:*")
                .collectList()
                .block();

        assertThat(redisKeys).isNotNull().hasSize(1);

        String redisKey = redisKeys.get(0);
        Map<Object, Object> bucketState = redisTemplate.opsForHash().entries(redisKey)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block();

        Duration ttl = redisTemplate.getExpire(redisKey).block();

        assertThat(bucketState).isNotNull();
        assertThat(bucketState).containsKeys("tokens", "last_refill");
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
    }

    @Test
    void logoutBlacklistsActiveTokenWithRemainingTtl() {
        WALLET_SERVICE.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"status\":\"logout-ok\"}"));

        String token = GatewayIntegrationTestSupport.createToken("user-456", "CUSTOMER", Duration.ofSeconds(90));
        String redisKey = "blacklist:" + token;

        webTestClient.post()
                .uri("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("logout-ok");

        Boolean isBlacklisted = redisTemplate.hasKey(redisKey).block();
        Duration ttl = redisTemplate.getExpire(redisKey).block();

        assertThat(isBlacklisted).isTrue();
        assertThat(ttl).isNotNull();
        assertThat(ttl).isPositive();
        assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(90));
    }
}
