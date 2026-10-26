package com.fincore.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.security.Principal;

/**
 * Redis token-bucket rate limiting.
 *  - API routes: keyed by the authenticated subject (falls back to the client address).
 *  - Auth routes: keyed by client address with a much smaller bucket (credential-stuffing protection).
 * Limitation: if Redis is unreachable the limiter FAILS OPEN (requests pass) so an outage of the cache does not take
 * down the bank; login brute force is additionally limited by account lockout in the identity service.
 * Behind a real load balancer the remote address must come from a trusted X-Forwarded-For hop (not configured here).
 */
@Configuration
public class RateLimitConfig {



    @Bean
    @Primary
    KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal().map(Principal::getName)
                .switchIfEmpty(Mono.fromSupplier(() -> clientAddress(exchange)));
    }

    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.fromSupplier(() -> clientAddress(exchange));
    }

    @Bean
    @Primary
    RedisRateLimiter apiRateLimiter(@Value("${fincore.ratelimit.api.replenish-rate:20}") int rate,
                                    @Value("${fincore.ratelimit.api.burst-capacity:40}") int burst) {
        return new RedisRateLimiter(rate, burst, 1);
    }

    @Bean
    RedisRateLimiter authRateLimiter(@Value("${fincore.ratelimit.auth.replenish-rate:2}") int rate,
                                     @Value("${fincore.ratelimit.auth.burst-capacity:5}") int burst) {
        return new RedisRateLimiter(rate, burst, 1);
    }

    private static String clientAddress(org.springframework.web.server.ServerWebExchange exchange) {
        var addr = exchange.getRequest().getRemoteAddress();
        return addr == null || addr.getAddress() == null ? "unknown" : addr.getAddress().getHostAddress();
    }
}
