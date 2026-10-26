package com.fincore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostingRulesTest {
    private static AccountState acct(AccountStatus status, String currency, String balance) {
        return new AccountState(UUID.randomUUID(), AccountKind.LIABILITY, status, currency,
                new AccountBalances(new BigDecimal(balance), new BigDecimal(balance)));
    }

    private final Money rs100 = Money.of("100", "INR");

    @Test
    void healthyTransferIsAllowed() {
        assertTrue(PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "500"), acct(AccountStatus.ACTIVE, "INR", "0"), rs100).isEmpty());
    }

    @Test
    void insufficientFundsIsRejected() {
        assertEquals("INSUFFICIENT_FUNDS", PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "99.99"), acct(AccountStatus.ACTIVE, "INR", "0"), rs100).orElseThrow());
    }

    @Test
    void frozenSourceCannotSpendButFrozenDestinationCanReceive() {
        assertEquals("SOURCE_ACCOUNT_NOT_ACTIVE", PostingRules.reserveRejection(acct(AccountStatus.FROZEN, "INR", "500"), acct(AccountStatus.ACTIVE, "INR", "0"), rs100).orElseThrow());
        assertTrue(PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "500"), acct(AccountStatus.FROZEN, "INR", "0"), rs100).isEmpty());
    }

    @Test
    void closedDestinationIsRejected() {
        assertEquals("DESTINATION_ACCOUNT_CLOSED", PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "500"), acct(AccountStatus.CLOSED, "INR", "0"), rs100).orElseThrow());
    }

    @Test
    void currencyMismatchIsRejected() {
        assertEquals("CURRENCY_MISMATCH", PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "500"), acct(AccountStatus.ACTIVE, "USD", "0"), rs100).orElseThrow());
    }

    @Test
    void missingAccountsAreReported() {
        assertEquals("SOURCE_ACCOUNT_NOT_FOUND", PostingRules.reserveRejection(null, acct(AccountStatus.ACTIVE, "INR", "0"), rs100).orElseThrow());
        assertEquals("DESTINATION_ACCOUNT_NOT_FOUND", PostingRules.reserveRejection(acct(AccountStatus.ACTIVE, "INR", "5"), null, rs100).orElseThrow());
    }

    @Test
    void reversalNeedsFundsAtTheDestination() {
        assertEquals("INSUFFICIENT_FUNDS_FOR_REVERSAL", PostingRules.reversalRejection(
                acct(AccountStatus.ACTIVE, "INR", "0"), acct(AccountStatus.ACTIVE, "INR", "50"), new BigDecimal("100")).orElseThrow());
        assertTrue(PostingRules.reversalRejection(
                acct(AccountStatus.ACTIVE, "INR", "0"), acct(AccountStatus.ACTIVE, "INR", "100"), new BigDecimal("100")).isEmpty());
    }

    @Test
    void postingIsRejectedIfDestinationClosedMeanwhile() {
        assertEquals("DESTINATION_ACCOUNT_CLOSED", PostingRules.postRejection(acct(AccountStatus.CLOSED, "INR", "0")).orElseThrow());
        assertTrue(PostingRules.postRejection(acct(AccountStatus.ACTIVE, "INR", "0")).isEmpty());
    }
}
