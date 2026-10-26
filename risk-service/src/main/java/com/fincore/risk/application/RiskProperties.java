package com.fincore.risk.application;

import com.fincore.risk.domain.RiskRules;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties("fincore.risk")
public record RiskProperties(
        @DefaultValue("500000") BigDecimal hardLimitPerTransfer,
        @DefaultValue("100000") BigDecimal reviewThreshold,
        @DefaultValue("1000000") BigDecimal dailyCumulativeLimit,
        @DefaultValue("10m") Duration velocityWindow,
        @DefaultValue("5") int velocityReviewCount,
        @DefaultValue("10") int velocityRejectCount,
        @DefaultValue("1h") Duration repeatWindow,
        @DefaultValue("3") int repeatReviewCount) {

    public RiskRules toRules() {
        return new RiskRules(hardLimitPerTransfer, reviewThreshold, dailyCumulativeLimit, velocityWindow,
                velocityReviewCount, velocityRejectCount, repeatWindow, repeatReviewCount);
    }
}
