package com.fincore.transaction.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Transfer state machine.
 *
 *   PENDING_REVIEW -> INITIATED | FAILED         (risk said REVIEW; an admin decides)
 *   INITIATED      -> PENDING | FAILED           (INITIATED: waiting for the ledger to reserve funds)
 *   PENDING        -> COMPLETED | FAILED         (PENDING: funds reserved, waiting for the journal to post)
 *   COMPLETED      -> REVERSING                  (admin requested a reversal)
 *   REVERSING      -> REVERSED | COMPLETED       (back to COMPLETED if the ledger could not reverse)
 *   FAILED, REVERSED are terminal.
 */
public enum TransferStatus {
    PENDING_REVIEW, INITIATED, PENDING, COMPLETED, FAILED, REVERSING, REVERSED;

    public Set<TransferStatus> allowedNext() {
        return switch (this) {
            case PENDING_REVIEW -> EnumSet.of(INITIATED, FAILED);
            case INITIATED -> EnumSet.of(PENDING, FAILED);
            case PENDING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.of(REVERSING);
            case REVERSING -> EnumSet.of(REVERSED, COMPLETED);
            case FAILED, REVERSED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canTransitionTo(TransferStatus next) {
        return allowedNext().contains(next);
    }

    public TransferStatus requireTransitionTo(TransferStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transfer transition " + this + " -> " + next);
        }
        return next;
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
