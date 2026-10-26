package com.fincore.ledger.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Seeded ASSET accounts that act as the counter-party of simulated deposits (see migration V1). */
public final class SystemAccounts {
    private static final Map<String, UUID> CASH_CLEARING = Map.of(
            "INR", UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "USD", UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private SystemAccounts() {}

    public static Optional<UUID> cashClearing(String currency) {
        return Optional.ofNullable(CASH_CLEARING.get(currency));
    }
}
