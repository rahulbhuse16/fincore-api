package com.fincore.risk.domain;

/** Ordered by severity: the final decision is the most severe among all triggered rules. */
public enum Decision {
    APPROVE, REVIEW, REJECT;

    public static Decision worst(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
