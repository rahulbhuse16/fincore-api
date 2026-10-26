package com.fincore.ledger.domain;

import java.util.UUID;

public record AccountState(UUID id, AccountKind kind, AccountStatus status, String currency, AccountBalances balances) {
    public AccountState withBalances(AccountBalances next) {
        return new AccountState(id, kind, status, currency, next);
    }
}
