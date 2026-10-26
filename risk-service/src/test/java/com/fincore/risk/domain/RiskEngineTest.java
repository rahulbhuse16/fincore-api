package com.fincore.risk.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {
    private final RiskRules rules = RiskRules.defaults();

    private RiskRequest req(String amount) {
        return new RiskRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "INR");
    }

    private RiskResult eval(String amount, RiskHistory h) {
        return RiskEngine.evaluate(req(amount), h, rules);
    }

    @Test
    void ordinaryTransferIsApprovedWithNoHits() {
        RiskResult r = eval("1000", RiskHistory.empty());
        assertEquals(Decision.APPROVE, r.decision());
        assertTrue(r.hits().isEmpty());
        assertEquals(0, r.score());
    }

    @Test
    void amountOverReviewThresholdIsReviewed() {
        assertEquals(Decision.APPROVE, eval("100000", RiskHistory.empty()).decision());      // boundary is inclusive-safe
        RiskResult r = eval("100000.01", RiskHistory.empty());
        assertEquals(Decision.REVIEW, r.decision());
        assertEquals(java.util.List.of("AMOUNT_OVER_REVIEW_THRESHOLD"), r.reasons());
    }

    @Test
    void amountOverHardLimitIsRejectedNotJustReviewed() {
        RiskResult r = eval("500000.01", RiskHistory.empty());
        assertEquals(Decision.REJECT, r.decision());
        assertEquals(java.util.List.of("AMOUNT_OVER_HARD_LIMIT"), r.reasons()); // review rule does not also fire
    }

    @Test
    void dailyCumulativeLimitIncludesTheCurrentAmount() {
        RiskHistory h = new RiskHistory(0, new BigDecimal("950000"), 0);
        assertEquals(Decision.APPROVE, eval("50000", h).decision());   // exactly at the limit
        RiskResult r = eval("50000.01", h);
        assertEquals(Decision.REJECT, r.decision());
        assertTrue(r.reasons().contains("DAILY_CUMULATIVE_EXCEEDED"));
    }

    @Test
    void velocityEscalatesFromReviewToReject() {
        assertEquals(Decision.APPROVE, eval("10", new RiskHistory(4, BigDecimal.ZERO, 0)).decision());
        RiskResult review = eval("10", new RiskHistory(5, BigDecimal.ZERO, 0));
        assertEquals(Decision.REVIEW, review.decision());
        assertEquals(java.util.List.of("VELOCITY_HIGH"), review.reasons());
        RiskResult reject = eval("10", new RiskHistory(10, BigDecimal.ZERO, 0));
        assertEquals(Decision.REJECT, reject.decision());
        assertEquals(java.util.List.of("VELOCITY_EXCEEDED"), reject.reasons());
    }

    @Test
    void repeatedIdenticalTransfersAreReviewed() {
        assertEquals(Decision.APPROVE, eval("10", new RiskHistory(0, BigDecimal.ZERO, 2)).decision());
        RiskResult r = eval("10", new RiskHistory(0, BigDecimal.ZERO, 3));
        assertEquals(Decision.REVIEW, r.decision());
        assertEquals(java.util.List.of("REPEATED_TRANSFER"), r.reasons());
    }

    @Test
    void mostSevereRuleWinsAndAllHitsAreReported() {
        RiskResult r = eval("150000", new RiskHistory(5, BigDecimal.ZERO, 3)); // review x3
        assertEquals(Decision.REVIEW, r.decision());
        assertEquals(3, r.hits().size());
        RiskResult worse = eval("150000", new RiskHistory(10, BigDecimal.ZERO, 3)); // reject + review
        assertEquals(Decision.REJECT, worse.decision());
    }

    @Test
    void scoreIsCappedAt100() {
        RiskResult r = eval("600000", new RiskHistory(10, new BigDecimal("999999"), 5));
        assertEquals(100, r.score());
    }

    @Test
    void evaluationIsDeterministic() {
        RiskRequest request = req("123456.78");
        RiskHistory h = new RiskHistory(6, new BigDecimal("1000"), 1);
        assertEquals(RiskEngine.evaluate(request, h, rules), RiskEngine.evaluate(request, h, rules));
    }

    @Test
    void customRulesAreHonoured() {
        RiskRules strict = new RiskRules(new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("2000"),
                java.time.Duration.ofMinutes(1), 1, 2, java.time.Duration.ofMinutes(1), 1);
        assertEquals(Decision.REVIEW, RiskEngine.evaluate(req("600"), RiskHistory.empty(), strict).decision());
        assertEquals(Decision.REJECT, RiskEngine.evaluate(req("1001"), RiskHistory.empty(), strict).decision());
    }
}
