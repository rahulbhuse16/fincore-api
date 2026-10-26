package com.fincore.transaction.client;

/** Timeout, connection failure or 5xx from a dependency. Counts as a failure for circuit breakers and may be retried. */
public class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
