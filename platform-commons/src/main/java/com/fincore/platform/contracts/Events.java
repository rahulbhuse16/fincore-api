package com.fincore.platform.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Explicit event payload schemas (schemaVersion 1). Consumers ignore unknown fields, so adding optional fields
 * is backward compatible; removing or retyping a field requires a new schemaVersion.
 */
public final class Events {
    private Events() {}

    /** account.opened - key: accountId */
    public record AccountOpened(UUID accountId, UUID customerId, String currency) {}

    /** account.status.changed - key: accountId. status in ACTIVE, FROZEN, CLOSED */
    public record AccountStatusChanged(UUID accountId, String status) {}

    /** transfer.initiated - key: transferRef. Command to the ledger: reserve funds. */
    public record TransferInitiated(UUID transferRef, UUID sourceAccountId, UUID destinationAccountId,
                                    BigDecimal amount, String currency) {}

    /** ledger.funds.reserved - key: transferRef */
    public record FundsReserved(UUID transferRef) {}

    /** ledger.funds.rejected - key: transferRef */
    public record FundsRejected(UUID transferRef, String reasonCode) {}

    /** ledger.posting.requested - key: transferRef. Command: convert the hold into posted journal entries. */
    public record PostingRequested(UUID transferRef) {}

    /** ledger.posting.completed - key: transferRef */
    public record PostingCompleted(UUID transferRef, UUID journalId) {}

    /** ledger.posting.failed - key: transferRef. The hold has been released. */
    public record PostingFailed(UUID transferRef, String reasonCode) {}

    /** transfer.reversal.requested - key: transferRef */
    public record ReversalRequested(UUID transferRef, String reason) {}

    /** ledger.reversal.completed - key: transferRef */
    public record ReversalCompleted(UUID transferRef, UUID reversalJournalId) {}

    /** ledger.reversal.failed - key: transferRef */
    public record ReversalFailed(UUID transferRef, String reasonCode) {}

    /** transfer.completed / transfer.failed / transfer.reversed - key: transferRef */
    public record TransferOutcome(UUID transferRef, String status, UUID sourceCustomerId, UUID destinationCustomerId,
                                  UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency,
                                  String description, String failureReason, Instant occurredAt) {}

    /** settlement.received - key: batchId. One settlement line from an external (synthetic) settlement file. */
    public record SettlementReceived(String batchId, int lineNo, String settlementRef, UUID transferRef,
                                     BigDecimal amount, String currency, String status, LocalDate settlementDate) {}
}
