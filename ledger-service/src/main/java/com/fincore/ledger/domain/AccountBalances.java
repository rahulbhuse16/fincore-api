package com.fincore.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The two balances of an account and the invariants between them:
 *
 *   ledger    = sum of POSTED entries (what the books say)
 *   available = ledger - holds      (what can still be spent)
 *   0 <= available <= ledger
 *
 * Every operation returns a NEW instance or throws; an invalid state is unrepresentable.
 * The same invariants are enforced again by CHECK constraints in PostgreSQL.
 */
public record AccountBalances(BigDecimal ledger, BigDecimal available) {

    public AccountBalances {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(available, "available");
        if (available.signum() < 0) {
            throw new IllegalStateException("available balance would be negative: " + available);
        }
        if (available.compareTo(ledger) > 0) {
            throw new IllegalStateException("available balance " + available + " exceeds ledger balance " + ledger);
        }
    }

    public static AccountBalances zero() {
        return new AccountBalances(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal held() {
        return ledger.subtract(available);
    }

    /** Place a hold: available decreases, ledger is untouched. */
    public AccountBalances reserve(BigDecimal amount) {
        requirePositive(amount);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFundsException("available " + available + " < requested " + amount);
        }
        return new AccountBalances(ledger, available.subtract(amount));
    }

    /** Cancel a hold: available increases again. */
    public AccountBalances release(BigDecimal amount) {
        requirePositive(amount);
        if (held().compareTo(amount) < 0) {
            throw new IllegalStateException("cannot release " + amount + ", only " + held() + " is held");
        }
        return new AccountBalances(ledger, available.add(amount));
    }

    /** Post a debit that was previously reserved: ledger decreases, available already did. */
    public AccountBalances settleHold(BigDecimal amount) {
        requirePositive(amount);
        if (held().compareTo(amount) < 0) {
            throw new IllegalStateException("cannot settle " + amount + ", only " + held() + " is held");
        }
        return new AccountBalances(ledger.subtract(amount), available);
    }

    /** Money arrives: both balances grow. */
    public AccountBalances increase(BigDecimal amount) {
        requirePositive(amount);
        return new AccountBalances(ledger.add(amount), available.add(amount));
    }

    /** Un-reserved money leaves (reversals, system accounts): needs available funds. */
    public AccountBalances decrease(BigDecimal amount) {
        requirePositive(amount);
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFundsException("available " + available + " < requested " + amount);
        }
        return new AccountBalances(ledger.subtract(amount), available.subtract(amount));
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
