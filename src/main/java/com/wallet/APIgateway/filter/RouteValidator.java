package com.wallet.APIgateway.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
public class RouteValidator {

    // Defines endpoints that bypass JWT verification
    public static final List<String> openApiEndpoints = List.of(
            "/api/v1/auth/",
            "/api/v1/webhooks/",
            "/v3/api-docs",
            "/swagger-ui"
    );

    public Predicate<ServerHttpRequest> isSecured =
            request -> {
                String path = request.getURI().getPath();
                // Explicitly isolate ALL teardown routes from the public auth whitelist
                if (path.contains("/api/v1/auth/logout") || path.contains("/api/v1/auth/close-account")) {
                    return true;
                }
                return openApiEndpoints.stream().noneMatch(path::contains);
            };
}
