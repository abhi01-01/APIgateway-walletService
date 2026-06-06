package com.wallet.APIgateway.filter;

import com.wallet.APIgateway.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Test
    void trustsXForwardedForOnlyWhenImmediateCallerIsTrustedProxy() {
        ClientIpResolver resolver = resolverWithTrustedProxies("10.0.0.0/8");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("10.0.0.5", 8080))
                        .header("X-Forwarded-For", "198.51.100.20, 10.0.0.5")
                        .build()
        );

        assertThat(resolver.resolve(exchange)).isEqualTo("198.51.100.20");
    }

    @Test
    void ignoresXForwardedForWhenImmediateCallerIsNotTrusted() {
        ClientIpResolver resolver = resolverWithTrustedProxies("10.0.0.0/8");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.9", 8080))
                        .header("X-Forwarded-For", "198.51.100.20")
                        .build()
        );

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedHeaderIsAbsent() {
        ClientIpResolver resolver = resolverWithTrustedProxies("10.0.0.0/8");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("203.0.113.10", 8080))
                        .build()
        );

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.10");
    }

    @Test
    void returnsUnknownWhenNoForwardedHeaderAndNoRemoteAddress() {
        ClientIpResolver resolver = resolverWithTrustedProxies();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/auth/login").build()
        );

        assertThat(resolver.resolve(exchange)).isEqualTo("unknown");
    }

    private ClientIpResolver resolverWithTrustedProxies(String... trustedProxies) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setTrustedProxies(List.of(trustedProxies));
        return new ClientIpResolver(properties);
    }
}
