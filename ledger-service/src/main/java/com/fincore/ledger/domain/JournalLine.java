package com.fincore.ledger.domain;

import java.util.UUID;

public record JournalLine(UUID accountId, Direction direction, Money amount) {
    public JournalLine {
        if (accountId == null || direction == null || amount == null) {
            throw new IllegalArgumentException("accountId, direction and amount are required");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("journal line amounts must be positive");
        }
    }

    public JournalLine opposite() {
        return new JournalLine(accountId, direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT, amount);
    }
}
