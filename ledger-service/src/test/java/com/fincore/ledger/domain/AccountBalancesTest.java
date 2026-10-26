package com.fincore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountBalancesTest {
    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void reservationReducesAvailableButNotLedger() {
        AccountBalances b = new AccountBalances(d("1000"), d("1000")).reserve(d("400"));
        assertEquals(0, b.ledger().compareTo(d("1000")));
        assertEquals(0, b.available().compareTo(d("600")));
        assertEquals(0, b.held().compareTo(d("400")));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        AccountBalances b = new AccountBalances(d("1000"), d("1000")).reserve(d("900"));
        assertThrows(InsufficientFundsException.class, () -> b.reserve(d("101")));
        assertDoesNotThrow(() -> b.reserve(d("100"))); // exactly the remainder is fine
    }

    @Test
    void settlingAHoldReducesLedgerOnly() {
        AccountBalances b = new AccountBalances(d("1000"), d("1000")).reserve(d("400")).settleHold(d("400"));
        assertEquals(0, b.ledger().compareTo(d("600")));
        assertEquals(0, b.available().compareTo(d("600")));
        assertEquals(0, b.held().signum());
    }

    @Test
    void releasingAHoldRestoresAvailable() {
        AccountBalances start = new AccountBalances(d("1000"), d("1000"));
        AccountBalances b = start.reserve(d("250")).release(d("250"));
        assertEquals(0, b.available().compareTo(start.available()));
    }

    @Test
    void cannotSettleOrReleaseMoreThanHeld() {
        AccountBalances b = new AccountBalances(d("1000"), d("1000")).reserve(d("100"));
        assertThrows(IllegalStateException.class, () -> b.settleHold(d("101")));
        assertThrows(IllegalStateException.class, () -> b.release(d("101")));
    }

    @Test
    void invalidStatesAreUnrepresentable() {
        assertThrows(IllegalStateException.class, () -> new AccountBalances(d("10"), d("-1")));
        assertThrows(IllegalStateException.class, () -> new AccountBalances(d("10"), d("11")));
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        AccountBalances b = AccountBalances.zero();
        assertThrows(IllegalArgumentException.class, () -> b.increase(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> b.increase(d("-5")));
    }

    @Test
    void decreaseNeedsAvailableFundsNotJustLedgerFunds() {
        AccountBalances b = new AccountBalances(d("100"), d("100")).reserve(d("80"));
        assertThrows(InsufficientFundsException.class, () -> b.decrease(d("50")));
    }
}
