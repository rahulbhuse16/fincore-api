package com.fincore.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class IdentityDomainTest {

    @Test
    void strongPasswordHasNoViolations() {
        assertTrue(PasswordPolicy.violations("Tr0ub4dor&Horse!", "alice").isEmpty());
    }

    @Test
    void shortPasswordIsRejected() {
        assertFalse(PasswordPolicy.violations("Ab1!", "alice").isEmpty());
        assertFalse(PasswordPolicy.violations(null, "alice").isEmpty());
    }

    @Test
    void eachMissingCharacterClassIsReported() {
        assertTrue(PasswordPolicy.violations("alllowercase123!", "x").contains("an uppercase letter"));
        assertTrue(PasswordPolicy.violations("ALLUPPERCASE123!", "x").contains("a lowercase letter"));
        assertTrue(PasswordPolicy.violations("NoDigitsHere!!!!", "x").contains("a digit"));
        assertTrue(PasswordPolicy.violations("NoSymbols123456", "x").contains("a symbol"));
    }

    @Test
    void passwordMustNotContainUsername() {
        assertTrue(PasswordPolicy.violations("Alice-Secret-123!", "alice").contains("must not contain the username"));
    }

    @Test
    void overlongPasswordIsRejectedBecauseBcryptTruncatesAt72Bytes() {
        assertTrue(PasswordPolicy.violations("Aa1!" + "x".repeat(70), "u").contains("at most 72 characters"));
    }

    @Test
    void lockoutAfterFiveConsecutiveFailures() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        int failures = 0;
        for (int i = 1; i <= 4; i++) {
            LockoutPolicy.State s = LockoutPolicy.afterFailure(failures, now);
            assertNull(s.lockedUntil());
            failures = s.failedAttempts();
        }
        LockoutPolicy.State locked = LockoutPolicy.afterFailure(failures, now);
        assertEquals(now.plus(LockoutPolicy.LOCK_DURATION), locked.lockedUntil());
        assertEquals(0, locked.failedAttempts());
    }

    @Test
    void lockExpiresAndSuccessResetsCounters() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        Instant until = now.plus(LockoutPolicy.LOCK_DURATION);
        assertTrue(LockoutPolicy.isLocked(until, now.plusSeconds(60)));
        assertFalse(LockoutPolicy.isLocked(until, until));
        assertFalse(LockoutPolicy.isLocked(null, now));
        assertEquals(0, LockoutPolicy.afterSuccess().failedAttempts());
    }
}
