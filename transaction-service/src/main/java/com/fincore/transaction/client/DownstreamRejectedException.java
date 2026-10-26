package com.fincore.transaction.client;

/** 4xx from a dependency: a definitive answer, never retried and never counted against the circuit breaker. */
public class DownstreamRejectedException extends RuntimeException {
    private final int status;

    public DownstreamRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
