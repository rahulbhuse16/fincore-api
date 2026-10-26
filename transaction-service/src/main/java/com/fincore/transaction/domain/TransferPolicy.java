package com.fincore.transaction.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/** Pre-conditions for creating a transfer. Pure and deterministic. Throws TransferRuleViolation with a stable code. */
public final class TransferPolicy {
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");

    private TransferPolicy() {}

    public static void validate(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String currency,
                                AccountView source, AccountView destination, BigDecimal alreadyTransferredToday) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new TransferRuleViolation("SAME_ACCOUNT", "Source and destination accounts must differ.");
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            throw new TransferRuleViolation("AMOUNT_NOT_POSITIVE", "Amount must be at least 0.01.");
        }
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > digits) {
            throw new TransferRuleViolation("AMOUNT_PRECISION", "Amount supports at most " + digits + " decimal places for " + currency + ".");
        }
        if (!currency.equals(source.currency()) || !currency.equals(destination.currency())) {
            throw new TransferRuleViolation("CURRENCY_MISMATCH", "Transfer currency must equal both account currencies (no FX in this simulation).");
        }
        if (!"ACTIVE".equals(source.status())) {
            throw new TransferRuleViolation("SOURCE_NOT_ACTIVE", "Source account is not active.");
        }
        if ("CLOSED".equals(destination.status())) {
            throw new TransferRuleViolation("DESTINATION_NOT_ACTIVE", "Destination account is closed.");
        }
        if (amount.compareTo(source.perTransferLimit()) > 0) {
            throw new TransferRuleViolation("PER_TRANSFER_LIMIT_EXCEEDED", "Amount exceeds the per-transfer limit of the source account.");
        }
        if (alreadyTransferredToday.add(amount).compareTo(source.dailyLimit()) > 0) {
            throw new TransferRuleViolation("DAILY_LIMIT_EXCEEDED", "Amount would exceed the daily limit of the source account.");
        }
    }
}
