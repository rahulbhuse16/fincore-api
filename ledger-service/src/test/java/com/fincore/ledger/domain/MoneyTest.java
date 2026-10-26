package com.fincore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void normalisesTrailingZerosToCurrencyScale() {
        assertEquals("1000.00", Money.of("1000", "INR").amount().toPlainString());
        assertEquals("1000.00", new Money(new BigDecimal("1000.0000"), "INR").amount().toPlainString());
    }

    @Test
    void rejectsExcessPrecisionInsteadOfRounding() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Money.of("10.005", "INR"));
        assertTrue(e.getMessage().contains("more than 2 decimal places"));
    }

    @Test
    void zeroDecimalCurrenciesAreHonoured() {
        assertEquals("500", Money.of("500", "JPY").amount().toPlainString());
        assertThrows(IllegalArgumentException.class, () -> Money.of("500.5", "JPY"));
    }

    @Test
    void explicitRoundingUsesTheGivenMode() {
        assertEquals("0.12", Money.ofRounded(new BigDecimal("0.125"), "INR", RoundingMode.HALF_EVEN).amount().toPlainString());
        assertEquals("0.13", Money.ofRounded(new BigDecimal("0.125"), "INR", RoundingMode.HALF_UP).amount().toPlainString());
    }

    @Test
    void arithmeticIsExactWhereDoublesWouldDrift() {
        Money sum = Money.of("0.10", "INR").add(Money.of("0.20", "INR"));
        assertEquals(0, sum.compareTo(Money.of("0.30", "INR"))); // 0.1 + 0.2 != 0.3 with double
    }

    @Test
    void differentCurrenciesCannotBeCombined() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "INR").add(Money.of("1", "USD")));
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "INR").compareTo(Money.of("1", "USD")));
    }

    @Test
    void unknownCurrencyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", "XXQ"));
    }
}
