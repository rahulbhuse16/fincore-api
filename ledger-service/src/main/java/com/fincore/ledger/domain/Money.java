package com.fincore.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary amount. The scale is fixed to the currency's minor unit (2 for INR/USD) and extra precision is
 * REJECTED rather than silently rounded: 10.005 is a client error, not 10.00 or 10.01.
 * Rounding, where genuinely needed (e.g. fees), must be explicit via {@link #ofRounded}.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        int digits = Currency.getInstance(currency).getDefaultFractionDigits(); // IllegalArgumentException if unknown
        try {
            amount = amount.setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more than " + digits + " decimal places for " + currency);
        }
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /** Explicit, documented rounding (banker's rounding by default in the financial domain). */
    public static Money ofRounded(BigDecimal value, String currency, RoundingMode mode) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return new Money(value.setScale(digits, mode), currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
