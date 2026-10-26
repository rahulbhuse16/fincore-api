package com.fincore.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/** Turns routing errors (no route, upstream down, timeout) into safe problem+json; no stack traces or hostnames leak. */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatusCode status = ex instanceof ResponseStatusException rse ? rse.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        HttpStatus resolved = HttpStatus.resolve(status.value()) != null ? HttpStatus.resolve(status.value()) : HttpStatus.INTERNAL_SERVER_ERROR;
        if (resolved.is5xxServerError()) {
            log.error("Gateway error {} on {}", resolved.value(), exchange.getRequest().getPath(), ex);
        }
        return switch (resolved) {
            case NOT_FOUND -> ProblemResponses.write(exchange, resolved, "not-found", "No such resource.");
            case SERVICE_UNAVAILABLE, BAD_GATEWAY, GATEWAY_TIMEOUT ->
                    ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable", "The service is temporarily unavailable.");
            case TOO_MANY_REQUESTS -> ProblemResponses.write(exchange, resolved, "rate-limited", "Too many requests.");
            default -> resolved.is4xxClientError()
                    ? ProblemResponses.write(exchange, resolved, "bad-request", "The request could not be processed.")
                    : ProblemResponses.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "An unexpected error occurred.");
        };
    }
}
