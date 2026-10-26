package com.fincore.transaction.client;

import com.fincore.platform.security.TokenIssuer;
import com.fincore.platform.web.ApiException;
import com.fincore.transaction.domain.AccountView;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads account facts from account-service. A read is naturally idempotent, so bounded retries with exponential
 * backoff + jitter are safe. If the dependency stays down the caller gets 503: we never guess about ownership or limits.
 */
@Component
public class AccountClient {
    private final RestClient client;
    private final TokenIssuer tokens;

    public AccountClient(RestClient accountRestClient, TokenIssuer tokens) {
        this.client = accountRestClient;
        this.tokens = tokens;
    }

    @Retry(name = "account", fallbackMethod = "findFallback")
    @CircuitBreaker(name = "account")
    public Optional<AccountView> find(UUID accountId) {
        try {
            return client.get().uri("/internal/v1/accounts/{id}/transfer-view", accountId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issueServiceToken("transaction-service"))
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (status == 404) {
                            return Optional.<AccountView>empty();
                        }
                        if (res.getStatusCode().is2xxSuccessful()) {
                            return Optional.ofNullable(res.bodyTo(AccountView.class));
                        }
                        if (res.getStatusCode().is4xxClientError()) {
                            throw new DownstreamRejectedException(status, "account-service rejected the request");
                        }
                        throw new DownstreamUnavailableException("account-service returned " + status, null);
                    });
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("account-service unreachable", e);
        }
    }

    /** Resilience4j fallback: must stay public so the AOP proxy can resolve it. */
    public Optional<AccountView> findFallback(UUID accountId, Throwable t) {
        if (t instanceof ApiException api) throw api;
        throw ApiException.unavailable("account-service-unavailable",
                "Account information is temporarily unavailable. Nothing was changed; retry with the same Idempotency-Key.");
    }
}
