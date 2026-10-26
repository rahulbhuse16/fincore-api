package com.fincore.transaction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferFingerprintTest {
    private final UUID customer = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void sameCustomerAndKeyAlwaysGiveTheSameReference() {
        assertEquals(TransferFingerprint.reference(customer, "key-0001"), TransferFingerprint.reference(customer, "key-0001"));
    }

    @Test
    void differentKeysOrCustomersGiveDifferentReferences() {
        assertNotEquals(TransferFingerprint.reference(customer, "key-0001"), TransferFingerprint.reference(customer, "key-0002"));
        assertNotEquals(TransferFingerprint.reference(customer, "key-0001"), TransferFingerprint.reference(UUID.randomUUID(), "key-0001"));
    }

    @Test
    void fingerprintIgnoresNumericFormatting() {
        assertEquals(TransferFingerprint.of(a, b, new BigDecimal("1000"), "INR", "rent"),
                TransferFingerprint.of(a, b, new BigDecimal("1000.00"), "INR", " rent "));
    }

    @Test
    void anyChangedFieldChangesTheFingerprint() {
        String base = TransferFingerprint.of(a, b, new BigDecimal("1000"), "INR", "rent");
        assertNotEquals(base, TransferFingerprint.of(a, b, new BigDecimal("1000.01"), "INR", "rent"));
        assertNotEquals(base, TransferFingerprint.of(b, a, new BigDecimal("1000"), "INR", "rent"));
        assertNotEquals(base, TransferFingerprint.of(a, b, new BigDecimal("1000"), "USD", "rent"));
        assertNotEquals(base, TransferFingerprint.of(a, b, new BigDecimal("1000"), "INR", "food"));
    }
}
