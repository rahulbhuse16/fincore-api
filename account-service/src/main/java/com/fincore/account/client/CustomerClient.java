package com.fincore.account.client;

import com.fincore.platform.security.TokenIssuer;
import com.fincore.platform.web.ApiException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * KYC lookup (read-only, so retries are safe). FAIL-CLOSED: if the customer service cannot be reached the account is
 * NOT opened; opening an account for an unverified customer is worse than asking the caller to retry.
 */
@Component
public class CustomerClient {
    public record KycView(UUID customerId, String kycStatus, String status) {}

    /** Thrown for timeouts/5xx so the circuit breaker and retry see them. */
    public static class CustomerServiceUnavailableException extends RuntimeException {
        public CustomerServiceUnavailableException(String m, Throwable c) { super(m, c); }
    }

    private final RestClient client;
    private final TokenIssuer tokens;

    public CustomerClient(@Value("${fincore.downstream.customer.base-url}") String baseUrl,
                          @Value("${fincore.downstream.customer.read-timeout-ms:2000}") long readMs, TokenIssuer tokens) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readMs));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @Retry(name = "customer", fallbackMethod = "fallback")
    @CircuitBreaker(name = "customer")
    public Optional<KycView> kyc(UUID customerId) {
        try {
            return client.get().uri("/internal/v1/customers/{id}/kyc-status", customerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueServiceToken("account-service"))
                    .exchange((req, res) -> {
                        if (res.getStatusCode().value() == 404) return Optional.<KycView>empty();
                        if (res.getStatusCode().is2xxSuccessful()) return Optional.ofNullable(res.bodyTo(KycView.class));
                        throw new CustomerServiceUnavailableException("customer-service returned " + res.getStatusCode().value(), null);
                    });
        } catch (RestClientException e) {
            throw new CustomerServiceUnavailableException("customer-service unreachable", e);
        }
    }

    /** Resilience4j fallback: must stay public so the AOP proxy can resolve it. */
    public Optional<KycView> fallback(UUID customerId, Throwable t) {
        throw ApiException.unavailable("customer-service-unavailable",
                "Customer verification is temporarily unavailable. The account was not opened; please retry.");
    }
}
