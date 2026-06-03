package com.wallet.APIgateway.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class GatewayInfoController {

    @GetMapping("/")
    public Mono<ResponseEntity<Map<String, Object>>> getEdgeStatus() {
        return Mono.just(ResponseEntity.ok(
                Map.of(
                        "gateway", "Active",
                        "status", "ONLINE",
                        "perimeter_security", "ENABLED",
                        "timestamp", Instant.now().toString()
                )
        ));
    }

}
