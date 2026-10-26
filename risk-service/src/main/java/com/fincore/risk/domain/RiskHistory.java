package com.fincore.risk.domain;

import java.math.BigDecimal;

/** Facts about the source account's recent, non-rejected assessments (computed by the persistence layer). */
public record RiskHistory(int transfersInVelocityWindow, BigDecimal totalLast24h, int identicalTransfersInRepeatWindow) {
    public static RiskHistory empty() {
        return new RiskHistory(0, BigDecimal.ZERO, 0);
    }
}
