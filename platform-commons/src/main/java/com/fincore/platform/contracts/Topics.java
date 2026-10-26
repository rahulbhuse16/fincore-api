package com.fincore.platform.contracts;

import java.util.List;

/** Kafka topic names. The eventType carried in each envelope equals the topic name. */
public final class Topics {
    private Topics() {}

    public static final String ACCOUNT_OPENED = "account.opened";
    public static final String ACCOUNT_STATUS_CHANGED = "account.status.changed";
    public static final String TRANSFER_INITIATED = "transfer.initiated";
    public static final String LEDGER_FUNDS_RESERVED = "ledger.funds.reserved";
    public static final String LEDGER_FUNDS_REJECTED = "ledger.funds.rejected";
    public static final String LEDGER_POSTING_REQUESTED = "ledger.posting.requested";
    public static final String LEDGER_POSTING_COMPLETED = "ledger.posting.completed";
    public static final String LEDGER_POSTING_FAILED = "ledger.posting.failed";
    public static final String TRANSFER_REVERSAL_REQUESTED = "transfer.reversal.requested";
    public static final String LEDGER_REVERSAL_COMPLETED = "ledger.reversal.completed";
    public static final String LEDGER_REVERSAL_FAILED = "ledger.reversal.failed";
    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String TRANSFER_FAILED = "transfer.failed";
    public static final String TRANSFER_REVERSED = "transfer.reversed";
    public static final String SETTLEMENT_RECEIVED = "settlement.received";

    public static final List<String> ALL = List.of(
            ACCOUNT_OPENED, ACCOUNT_STATUS_CHANGED, TRANSFER_INITIATED, LEDGER_FUNDS_RESERVED,
            LEDGER_FUNDS_REJECTED, LEDGER_POSTING_REQUESTED, LEDGER_POSTING_COMPLETED, LEDGER_POSTING_FAILED,
            TRANSFER_REVERSAL_REQUESTED, LEDGER_REVERSAL_COMPLETED, LEDGER_REVERSAL_FAILED,
            TRANSFER_COMPLETED, TRANSFER_FAILED, TRANSFER_REVERSED, SETTLEMENT_RECEIVED);

    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}
