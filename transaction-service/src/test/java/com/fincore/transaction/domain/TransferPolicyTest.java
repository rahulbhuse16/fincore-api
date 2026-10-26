package com.fincore.transaction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferPolicyTest {
    private final UUID aId = UUID.randomUUID();
    private final UUID bId = UUID.randomUUID();
    private final AccountView src = new AccountView(aId, UUID.randomUUID(), "INR", "ACTIVE", new BigDecimal("50000"), new BigDecimal("200000"));
    private final AccountView dst = new AccountView(bId, UUID.randomUUID(), "INR", "ACTIVE", new BigDecimal("50000"), new BigDecimal("200000"));

    private void check(String amount, AccountView s, AccountView d, String usedToday) {
        TransferPolicy.validate(aId, bId, new BigDecimal(amount), "INR", s, d, new BigDecimal(usedToday));
    }

    private String codeOf(Runnable r) {
        return assertThrows(TransferRuleViolation.class, r::run).code();
    }

    @Test
    void validTransferPasses() {
        assertDoesNotThrow(() -> check("1000", src, dst, "0"));
    }

    @Test
    void limitsAreInclusive() {
        assertDoesNotThrow(() -> check("50000", src, dst, "0"));
        assertDoesNotThrow(() -> check("1000", src, dst, "199000"));
    }

    @Test
    void perTransferLimitIsEnforced() {
        assertEquals("PER_TRANSFER_LIMIT_EXCEEDED", codeOf(() -> check("50000.01", src, dst, "0")));
    }

    @Test
    void dailyLimitIncludesAlreadyTransferredAmount() {
        assertEquals("DAILY_LIMIT_EXCEEDED", codeOf(() -> check("1000.01", src, dst, "199000")));
    }

    @Test
    void selfTransferIsRejected() {
        assertEquals("SAME_ACCOUNT", codeOf(() -> TransferPolicy.validate(aId, aId, BigDecimal.TEN, "INR", src, src, BigDecimal.ZERO)));
    }

    @Test
    void amountMustBePositiveAndRespectCurrencyPrecision() {
        assertEquals("AMOUNT_NOT_POSITIVE", codeOf(() -> check("0", src, dst, "0")));
        assertEquals("AMOUNT_NOT_POSITIVE", codeOf(() -> check("-5", src, dst, "0")));
        assertEquals("AMOUNT_PRECISION", codeOf(() -> check("10.001", src, dst, "0")));
        assertDoesNotThrow(() -> check("10.10", src, dst, "0"));
    }

    @Test
    void currencyMustMatchBothAccounts() {
        AccountView usd = new AccountView(bId, UUID.randomUUID(), "USD", "ACTIVE", new BigDecimal("50000"), new BigDecimal("200000"));
        assertEquals("CURRENCY_MISMATCH", codeOf(() -> check("10", src, usd, "0")));
    }

    @Test
    void sourceMustBeActiveAndDestinationNotClosed() {
        AccountView frozen = new AccountView(aId, src.customerId(), "INR", "FROZEN", src.perTransferLimit(), src.dailyLimit());
        AccountView closed = new AccountView(bId, dst.customerId(), "INR", "CLOSED", dst.perTransferLimit(), dst.dailyLimit());
        AccountView frozenDst = new AccountView(bId, dst.customerId(), "INR", "FROZEN", dst.perTransferLimit(), dst.dailyLimit());
        assertEquals("SOURCE_NOT_ACTIVE", codeOf(() -> check("10", frozen, dst, "0")));
        assertEquals("DESTINATION_NOT_ACTIVE", codeOf(() -> check("10", src, closed, "0")));
        assertDoesNotThrow(() -> check("10", src, frozenDst, "0"));
    }
}
