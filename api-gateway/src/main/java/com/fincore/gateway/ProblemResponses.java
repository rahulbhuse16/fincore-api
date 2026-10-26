package com.fincore.gateway;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** RFC 9457 problem+json for errors produced at the edge, identical in shape to the services' errors. */
final class ProblemResponses {
    private ProblemResponses() {}

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        String correlationId = exchange.getResponse().getHeaders().getFirst(CorrelationIdGlobalFilter.HEADER);
        String json = "{\"type\":\"https://fincore.example/problems/" + code + "\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code
                + "\",\"timestamp\":\"" + Instant.now() + "\""
                + (correlationId != null ? ",\"correlationId\":\"" + correlationId + "\"" : "") + "}";
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
