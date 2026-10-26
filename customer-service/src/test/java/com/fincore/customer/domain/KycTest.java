package com.fincore.customer.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class KycTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Test
    void adultWithOrdinaryDocumentIsVerified() {
        var r = KycSimulator.evaluate(LocalDate.of(1990, 1, 1), "ABCDE1234F", TODAY);
        assertEquals(KycStatus.VERIFIED, r.status());
    }

    @Test
    void eighteenthBirthdayIsTheBoundary() {
        assertEquals(KycStatus.VERIFIED, KycSimulator.evaluate(LocalDate.of(2008, 10, 19), "ABCDE1234F", TODAY).status());
        var minor = KycSimulator.evaluate(LocalDate.of(2008, 10, 20), "ABCDE1234F", TODAY);
        assertEquals(KycStatus.REJECTED, minor.status());
        assertEquals("UNDERAGE", minor.reason());
    }

    @Test
    void failingAndManualDocumentPrefixesAreHonoured() {
        assertEquals(KycStatus.REJECTED, KycSimulator.evaluate(LocalDate.of(1990, 1, 1), "fail-123456", TODAY).status());
        var manual = KycSimulator.evaluate(LocalDate.of(1990, 1, 1), "MANUAL-123", TODAY);
        assertEquals(KycStatus.PENDING, manual.status());
    }

    @Test
    void shortOrMissingDocumentIsRejected() {
        assertEquals(KycStatus.REJECTED, KycSimulator.evaluate(LocalDate.of(1990, 1, 1), "123", TODAY).status());
        assertEquals(KycStatus.REJECTED, KycSimulator.evaluate(LocalDate.of(1990, 1, 1), null, TODAY).status());
    }

    @Test
    void transitionsFollowTheWorkflow() {
        assertTrue(KycStatus.PENDING.canTransitionTo(KycStatus.VERIFIED));
        assertTrue(KycStatus.PENDING.canTransitionTo(KycStatus.REJECTED));
        assertTrue(KycStatus.VERIFIED.canTransitionTo(KycStatus.REJECTED));
        assertTrue(KycStatus.REJECTED.canTransitionTo(KycStatus.PENDING));
        assertFalse(KycStatus.VERIFIED.canTransitionTo(KycStatus.PENDING));
        assertFalse(KycStatus.REJECTED.canTransitionTo(KycStatus.VERIFIED)); // must be re-reviewed first
        assertFalse(KycStatus.PENDING.canTransitionTo(KycStatus.PENDING));
    }
}
