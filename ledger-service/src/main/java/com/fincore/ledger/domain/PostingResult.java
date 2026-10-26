package com.fincore.ledger.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PostingResult(Map<UUID, AccountBalances> balancesAfter, List<AppliedLine> lines) {
    /** ledgerBalanceAfter is the account's LEDGER balance right after this line was applied. */
    public record AppliedLine(JournalLine line, java.math.BigDecimal ledgerBalanceAfter) {}
}
