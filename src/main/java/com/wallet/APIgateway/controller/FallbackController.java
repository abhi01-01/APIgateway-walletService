package com.wallet.APIgateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.io.Serializable;
import java.util.Map;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/walletService")
    public Mono<ResponseEntity<Map<String, Serializable>>> walletServiceFallback(){
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                Map.of(
                        "success", false,
                        "message", "Wallet Service is currently degraded or experiencing high load. Please try again later.",
                        "error_code", "SERVICE_UNAVAILABLE"
                )
        ));
    }
}
