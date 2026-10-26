package com.fincore.customer.domain;

import java.util.EnumSet;
import java.util.Set;

/** PENDING -> VERIFIED | REJECTED; VERIFIED -> REJECTED (revocation); REJECTED -> PENDING (re-submission). */
public enum KycStatus {
    PENDING, VERIFIED, REJECTED;

    public Set<KycStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(VERIFIED, REJECTED);
            case VERIFIED -> EnumSet.of(REJECTED);
            case REJECTED -> EnumSet.of(PENDING);
        };
    }

    public boolean canTransitionTo(KycStatus next) {
        return allowedNext().contains(next);
    }
}
