package com.fincore.ledger.api;

import com.fincore.ledger.domain.Direction;
import com.fincore.ledger.domain.JournalType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LedgerDtos {
    private LedgerDtos() {}

    public record BalanceResponse(UUID accountId, String currency, BigDecimal ledgerBalance, BigDecimal availableBalance,
                                  BigDecimal heldAmount, String status, Instant asOf) {}

    public record EntryResponse(UUID entryId, UUID journalId, Direction direction, BigDecimal amount, String currency,
                                BigDecimal ledgerBalanceAfter, Instant createdAt) {}

    public record JournalResponse(UUID journalId, JournalType type, UUID reference, UUID reversesJournalId,
                                  String currency, Instant postedAt, List<EntryResponse> entries) {}

    public record DepositRequest(
            @NotNull UUID reference,
            @NotNull UUID accountId,
            @NotNull @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
            @Digits(integer = 15, fraction = 2, message = "amount supports at most 2 decimal places") BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO-4217 code") String currency) {}

    public record DepositResponse(UUID journalId, boolean replay) {}
}
