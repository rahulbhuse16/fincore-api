package com.fincore.account.domain;

import java.util.EnumSet;
import java.util.Set;

/** ACTIVE <-> FROZEN; either -> CLOSED. CLOSED is terminal. */
public enum AccountStatus {
    ACTIVE, FROZEN, CLOSED;

    public Set<AccountStatus> allowedNext() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(FROZEN, CLOSED);
            case FROZEN -> EnumSet.of(ACTIVE, CLOSED);
            case CLOSED -> EnumSet.noneOf(AccountStatus.class);
        };
    }

    public boolean canTransitionTo(AccountStatus next) {
        return allowedNext().contains(next);
    }
}
