package com.fincore.risk.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic rule engine: the same request, history and rules always give the same result, with no clock,
 * randomness or I/O inside. Every triggered rule is returned, so a decision is fully explainable and auditable.
 *
 * Rules:
 *  AMOUNT_OVER_HARD_LIMIT      amount > hardLimitPerTransfer                      -> REJECT
 *  AMOUNT_OVER_REVIEW_THRESHOLD amount > reviewThreshold (and not over hard limit) -> REVIEW
 *  DAILY_CUMULATIVE_EXCEEDED   last24h + amount > dailyCumulativeLimit            -> REJECT
 *  VELOCITY_EXCEEDED           recent count >= velocityRejectCount                -> REJECT
 *  VELOCITY_HIGH               recent count >= velocityReviewCount                -> REVIEW
 *  REPEATED_TRANSFER           identical recent transfers >= repeatReviewCount    -> REVIEW
 */
public final class RiskEngine {
    private RiskEngine() {}

    public static RiskResult evaluate(RiskRequest request, RiskHistory history, RiskRules rules) {
        List<RuleHit> hits = new ArrayList<>();

        if (request.amount().compareTo(rules.hardLimitPerTransfer()) > 0) {
            hits.add(new RuleHit("AMOUNT_OVER_HARD_LIMIT", Decision.REJECT, 100, "Amount exceeds the absolute per-transfer limit"));
        } else if (request.amount().compareTo(rules.reviewThreshold()) > 0) {
            hits.add(new RuleHit("AMOUNT_OVER_REVIEW_THRESHOLD", Decision.REVIEW, 40, "Large amount requires manual review"));
        }

        if (history.totalLast24h().add(request.amount()).compareTo(rules.dailyCumulativeLimit()) > 0) {
            hits.add(new RuleHit("DAILY_CUMULATIVE_EXCEEDED", Decision.REJECT, 90, "Rolling 24h total would exceed the cumulative limit"));
        }

        if (history.transfersInVelocityWindow() >= rules.velocityRejectCount()) {
            hits.add(new RuleHit("VELOCITY_EXCEEDED", Decision.REJECT, 80, "Too many transfers in a short time"));
        } else if (history.transfersInVelocityWindow() >= rules.velocityReviewCount()) {
            hits.add(new RuleHit("VELOCITY_HIGH", Decision.REVIEW, 30, "Unusually many transfers in a short time"));
        }

        if (history.identicalTransfersInRepeatWindow() >= rules.repeatReviewCount()) {
            hits.add(new RuleHit("REPEATED_TRANSFER", Decision.REVIEW, 30, "Same amount to the same destination repeated"));
        }

        Decision decision = hits.stream().map(RuleHit::decision).reduce(Decision.APPROVE, Decision::worst);
        int score = Math.min(100, hits.stream().mapToInt(RuleHit::weight).sum());
        return new RiskResult(decision, score, List.copyOf(hits));
    }
}
