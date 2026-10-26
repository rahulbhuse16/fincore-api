package com.fincore.account.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountDomainTest {

    @Test
    void activeAndFrozenCanBeToggledAndClosed() {
        assertTrue(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.FROZEN));
        assertTrue(AccountStatus.FROZEN.canTransitionTo(AccountStatus.ACTIVE));
        assertTrue(AccountStatus.ACTIVE.canTransitionTo(AccountStatus.CLOSED));
        assertTrue(AccountStatus.FROZEN.canTransitionTo(AccountStatus.CLOSED));
    }

    @Test
    void closedIsTerminalAndSelfTransitionsAreRejected() {
        for (AccountStatus s : AccountStatus.values()) {
            assertFalse(AccountStatus.CLOSED.canTransitionTo(s));
            assertFalse(s.canTransitionTo(s));
        }
    }

    @Test
    void generatedAccountNumbersHaveTheExpectedShapeAndVary() {
        String a = AccountNumbers.next();
        String b = AccountNumbers.next();
        assertTrue(AccountNumbers.isValid(a), a);
        assertNotEquals(a, b);
        assertFalse(AccountNumbers.isValid("XX123"));
    }
}
