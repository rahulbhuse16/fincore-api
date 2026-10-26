package com.fincore.identity.domain;

import java.time.Duration;
import java.time.Instant;

/** Brute-force protection: 5 consecutive failures lock the account for 15 minutes. */
public final class LockoutPolicy {
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    public record State(int failedAttempts, Instant lockedUntil) {}

    private LockoutPolicy() {}

    public static boolean isLocked(Instant lockedUntil, Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public static State afterFailure(int previousFailures, Instant now) {
        int failures = previousFailures + 1;
        return failures >= MAX_FAILURES ? new State(0, now.plus(LOCK_DURATION)) : new State(failures, null);
    }

    public static State afterSuccess() {
        return new State(0, null);
    }
}
