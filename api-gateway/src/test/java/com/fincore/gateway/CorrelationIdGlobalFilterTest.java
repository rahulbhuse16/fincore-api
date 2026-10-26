package com.fincore.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdGlobalFilterTest {
    private final CorrelationIdGlobalFilter filter = new CorrelationIdGlobalFilter();

    private MockServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> req, AtomicReference<org.springframework.web.server.ServerWebExchange> seen) {
        MockServerWebExchange exchange = MockServerWebExchange.from(req);
        filter.filter(exchange, e -> { seen.set(e); return reactor.core.publisher.Mono.empty(); }).block();
        return exchange;
    }

    @Test
    void generatesACorrelationIdWhenNoneIsSupplied() {
        var seen = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        var exchange = run(MockServerHttpRequest.get("/api/v1/x"), seen);
        String id = seen.get().getRequest().getHeaders().getFirst("X-Correlation-Id");
        assertNotNull(id);
        assertEquals(id, exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void keepsAWellFormedClientCorrelationId() {
        var seen = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        run(MockServerHttpRequest.get("/x").header("X-Correlation-Id", "abc-12345678"), seen);
        assertEquals("abc-12345678", seen.get().getRequest().getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void replacesAMalformedOrMaliciousCorrelationId() {
        var seen = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        run(MockServerHttpRequest.get("/x").header("X-Correlation-Id", "bad id\r\nX-Evil: 1"), seen);
        String id = seen.get().getRequest().getHeaders().getFirst("X-Correlation-Id");
        assertTrue(id.matches("^[0-9a-f-]{36}$"), id);
    }

    @Test
    void stripsClientSuppliedIdentityHeaders() {
        var seen = new AtomicReference<org.springframework.web.server.ServerWebExchange>();
        run(MockServerHttpRequest.get("/x").header("X-User-Id", "admin").header("X-Roles", "ADMIN").header("X-Customer-Id", "42"), seen);
        var headers = seen.get().getRequest().getHeaders();
        assertNull(headers.getFirst("X-User-Id"));
        assertNull(headers.getFirst("X-Roles"));
        assertNull(headers.getFirst("X-Customer-Id"));
    }
}
