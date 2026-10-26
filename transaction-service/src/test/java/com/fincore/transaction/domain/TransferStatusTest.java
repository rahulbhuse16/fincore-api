package com.fincore.transaction.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.fincore.transaction.domain.TransferStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferStatusTest {

    @Test
    void happyPathIsAllowed() {
        assertTrue(INITIATED.canTransitionTo(PENDING));
        assertTrue(PENDING.canTransitionTo(COMPLETED));
        assertTrue(COMPLETED.canTransitionTo(REVERSING));
        assertTrue(REVERSING.canTransitionTo(REVERSED));
    }

    @Test
    void failureAndReviewPathsAreAllowed() {
        assertTrue(INITIATED.canTransitionTo(FAILED));
        assertTrue(PENDING.canTransitionTo(FAILED));
        assertTrue(PENDING_REVIEW.canTransitionTo(INITIATED));
        assertTrue(PENDING_REVIEW.canTransitionTo(FAILED));
        assertTrue(REVERSING.canTransitionTo(COMPLETED)); // reversal could not be executed
    }

    @Test
    void terminalStatesAllowNothing() {
        for (TransferStatus t : EnumSet.of(FAILED, REVERSED)) {
            assertTrue(t.isTerminal());
            for (TransferStatus next : TransferStatus.values()) {
                assertFalse(t.canTransitionTo(next), t + " -> " + next);
            }
        }
    }

    @Test
    void completedTransferCannotBeFailedOrSkipAhead() {
        assertFalse(COMPLETED.canTransitionTo(FAILED));
        assertFalse(INITIATED.canTransitionTo(COMPLETED));
        assertFalse(PENDING.canTransitionTo(REVERSED));
        assertFalse(COMPLETED.canTransitionTo(REVERSED)); // must pass through REVERSING
    }

    @Test
    void requireTransitionThrowsOnIllegalMove() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FAILED.requireTransitionTo(COMPLETED));
        assertTrue(e.getMessage().contains("FAILED -> COMPLETED"));
        assertEquals(PENDING, INITIATED.requireTransitionTo(PENDING));
    }
}
