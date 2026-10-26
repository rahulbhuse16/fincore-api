package com.fincore.ledger.domain;

import java.math.BigDecimal;
import java.util.Optional;

/** Business pre-conditions evaluated before balances are touched. Returns a reason code or empty when allowed. */
public final class PostingRules {
    private PostingRules() {}

    public static Optional<String> reserveRejection(AccountState source, AccountState destination, Money amount) {
        if (source == null) return Optional.of("SOURCE_ACCOUNT_NOT_FOUND");
        if (destination == null) return Optional.of("DESTINATION_ACCOUNT_NOT_FOUND");
        if (source.id().equals(destination.id())) return Optional.of("SAME_ACCOUNT");
        if (source.kind() != AccountKind.LIABILITY || destination.kind() != AccountKind.LIABILITY) {
            return Optional.of("NOT_A_CUSTOMER_ACCOUNT");
        }
        if (!source.currency().equals(amount.currency()) || !destination.currency().equals(amount.currency())) {
            return Optional.of("CURRENCY_MISMATCH");
        }
        if (source.status() != AccountStatus.ACTIVE) return Optional.of("SOURCE_ACCOUNT_NOT_ACTIVE");
        if (destination.status() == AccountStatus.CLOSED) return Optional.of("DESTINATION_ACCOUNT_CLOSED");
        if (source.balances().available().compareTo(amount.amount()) < 0) return Optional.of("INSUFFICIENT_FUNDS");
        return Optional.empty();
    }

    /** Re-checked at posting time: the destination may have been closed after the funds were reserved. */
    public static Optional<String> postRejection(AccountState destination) {
        return destination.status() == AccountStatus.CLOSED ? Optional.of("DESTINATION_ACCOUNT_CLOSED") : Optional.empty();
    }

    /** A reversal takes money back from the destination, which must still hold it. */
    public static Optional<String> reversalRejection(AccountState originalSource, AccountState originalDestination, BigDecimal amount) {
        if (originalSource.status() == AccountStatus.CLOSED) return Optional.of("SOURCE_ACCOUNT_CLOSED");
        if (originalDestination.balances().available().compareTo(amount) < 0) {
            return Optional.of("INSUFFICIENT_FUNDS_FOR_REVERSAL");
        }
        return Optional.empty();
    }
}
