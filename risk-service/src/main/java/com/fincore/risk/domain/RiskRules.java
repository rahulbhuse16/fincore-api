package com.fincore.risk.domain;

import java.math.BigDecimal;
import java.time.Duration;

/** Configurable thresholds (bound from fincore.risk.* properties). */
public record RiskRules(
        BigDecimal hardLimitPerTransfer,       // above this: REJECT
        BigDecimal reviewThreshold,            // above this: REVIEW
        BigDecimal dailyCumulativeLimit,       // 24h total including this transfer above this: REJECT
        Duration velocityWindow,
        int velocityReviewCount,               // this many recent transfers already: REVIEW
        int velocityRejectCount,               // this many recent transfers already: REJECT
        Duration repeatWindow,
        int repeatReviewCount) {               // identical (source, destination, amount) already seen this many times: REVIEW

    public static final String VERSION = "rules-v1";

    public static RiskRules defaults() {
        return new RiskRules(new BigDecimal("500000"), new BigDecimal("100000"), new BigDecimal("1000000"),
                Duration.ofMinutes(10), 5, 10, Duration.ofHours(1), 3);
    }
}
