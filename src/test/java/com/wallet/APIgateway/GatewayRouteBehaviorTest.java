package com.wallet.APIgateway;

import com.wallet.APIgateway.support.GatewayIntegrationTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("prod")
@Testcontainers(disabledWithoutDocker = true)
class GatewayRouteBehaviorTest {

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
    void publicAuthRouteBypassesJwtAndReachesDownstream() throws Exception {
        WALLET_SERVICE.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"status\":\"login-ok\"}"));

        webTestClient.get()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("login-ok");

        RecordedRequest recordedRequest = WALLET_SERVICE.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/auth/login");
        assertThat(recordedRequest.getHeader("X-User-Id")).isNull();
        assertThat(recordedRequest.getHeader("X-User-Role")).isNull();
    }

    @Test
    void teardownRouteRejectsMissingAuthorizationHeaderInProd() throws Exception {
        webTestClient.post()
                .uri("/api/v1/auth/logout")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(WALLET_SERVICE.takeRequest(250, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void securedRouteInjectsIdentityHeadersIntoDownstreamRequest() throws Exception {
        WALLET_SERVICE.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"status\":\"payment-ok\"}"));

        String token = GatewayIntegrationTestSupport.createToken("user-123", "CUSTOMER", Duration.ofMinutes(5));

        webTestClient.get()
                .uri("/api/v1/payments/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("payment-ok");

        RecordedRequest recordedRequest = WALLET_SERVICE.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getHeader("X-User-Id")).isEqualTo("user-123");
        assertThat(recordedRequest.getHeader("X-User-Role")).isEqualTo("CUSTOMER");
        assertThat(recordedRequest.getHeader("X-Gateway-Token"))
                .isEqualTo(GatewayIntegrationTestSupport.TEST_GATEWAY_SECRET);
    }

    @Test
    void downstreamConnectionFailureReturnsConfiguredFallbackShape() {
        WALLET_SERVICE.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        String token = GatewayIntegrationTestSupport.createToken("wallet-user", "CUSTOMER", Duration.ofMinutes(5));

        webTestClient.get()
                .uri("/api/v1/wallets/balance")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message")
                .isEqualTo("Wallet Service is currently degraded or experiencing high load. Please try again later.")
                .jsonPath("$.error_code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void adminMessagingRouteRequiresJwtAndReachesDownstreamWithIdentityHeaders() throws InterruptedException {
        WALLET_SERVICE.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"status\":\"admin-messaging-ok\"}"));

        String token = GatewayIntegrationTestSupport.createToken("system-1", "SYSTEM", Duration.ofMinutes(5));

        webTestClient.get()
                .uri("/api/v1/admin/messaging/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("admin-messaging-ok");

        RecordedRequest recordedRequest = WALLET_SERVICE.takeRequest(1, TimeUnit.SECONDS);

        assertThat(recordedRequest).isNotNull();
        assertThat(recordedRequest.getPath()).isEqualTo("/api/v1/admin/messaging/summary");
        assertThat(recordedRequest.getHeader("X-User-Id")).isEqualTo("system-1");
        assertThat(recordedRequest.getHeader("X-User-Role")).isEqualTo("SYSTEM");
        assertThat(recordedRequest.getHeader("X-Gateway-Token"))
                .isEqualTo(GatewayIntegrationTestSupport.TEST_GATEWAY_SECRET);
    }

    @Test
    void adminMessagingRouteRejectsMissingAuthorizationHeader() throws Exception {
        webTestClient.get()
                .uri("/api/v1/admin/messaging/summary")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(WALLET_SERVICE.takeRequest(250, TimeUnit.MILLISECONDS)).isNull();
    }
    
}
