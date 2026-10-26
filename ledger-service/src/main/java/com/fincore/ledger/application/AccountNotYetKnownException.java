package com.fincore.ledger.application;

import java.util.UUID;

/** The ledger projection of an account has not arrived yet (events are eventually consistent). Retryable. */
public class AccountNotYetKnownException extends RuntimeException {
    public AccountNotYetKnownException(UUID accountId) {
        super("Ledger account " + accountId + " is not known yet");
    }
}
