package com.fincore.transaction.client;

import com.fincore.platform.security.TokenIssuer;
import com.fincore.platform.web.ApiException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Synchronous risk check.
 *
 * FAIL-CLOSED policy: if the risk service cannot answer (timeout, circuit open, bulkhead full) the transfer is NOT
 * created and the client receives 503. A timeout is never converted into an approval. Retrying is safe because the
 * risk service deduplicates on referenceId (the deterministic transfer reference), and the client's own retry with
 * the same Idempotency-Key lands on the same reference.
 */
@Component
public class RiskClient {
    public record RiskRequest(UUID referenceId, UUID customerId, UUID sourceAccountId, UUID destinationAccountId,
                              BigDecimal amount, String currency) {}

    public record RiskResponse(UUID assessmentId, String decision, int score, List<String> reasons) {}

    private final RestClient client;
    private final TokenIssuer tokens;

    public RiskClient(RestClient riskRestClient, TokenIssuer tokens) {
        this.client = riskRestClient;
        this.tokens = tokens;
    }

    @Retry(name = "risk", fallbackMethod = "assessFallback")
    @CircuitBreaker(name = "risk")
    @Bulkhead(name = "risk")
    public RiskResponse assess(RiskRequest request) {
        try {
            return client.post().uri("/api/v1/risk/assessments")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueServiceToken("transaction-service"))
                    .body(request)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().is2xxSuccessful()) {
                            RiskResponse body = res.bodyTo(RiskResponse.class);
                            if (body == null) throw new DownstreamUnavailableException("empty risk response", null);
                            return body;
                        }
                        if (res.getStatusCode().is4xxClientError()) {
                            throw new DownstreamRejectedException(res.getStatusCode().value(), "risk-service rejected the request");
                        }
                        throw new DownstreamUnavailableException("risk-service returned " + res.getStatusCode().value(), null);
                    });
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("risk-service unreachable", e);
        }
    }

    /** Resilience4j fallback: must stay public so the AOP proxy can resolve it. */
    public RiskResponse assessFallback(RiskRequest request, Throwable t) {
        if (t instanceof ApiException api) throw api;
        throw ApiException.unavailable("risk-unavailable",
                "Risk assessment is temporarily unavailable, so the transfer was not created. Retry with the same Idempotency-Key.");
    }
}
