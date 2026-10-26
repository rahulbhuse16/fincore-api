package com.fincore.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Target of the circuit-breaker filter when a downstream is open/timed out. It answers 503 - it never fabricates a
 * success. For a financial write the client must retry with the SAME Idempotency-Key.
 */
@RestController
public class FallbackController {
    @RequestMapping("/fallback/unavailable")
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of("type", "https://fincore.example/problems/upstream-unavailable", "title", "Service Unavailable",
                        "status", 503, "code", "upstream-unavailable",
                        "detail", "The service is temporarily unavailable. The request outcome is unknown; for transfers retry with the same Idempotency-Key.",
                        "timestamp", Instant.now().toString()));
    }
}
