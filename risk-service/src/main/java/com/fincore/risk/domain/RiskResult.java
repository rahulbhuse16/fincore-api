package com.fincore.risk.domain;

import java.util.List;

public record RiskResult(Decision decision, int score, List<RuleHit> hits) {
    public List<String> reasons() {
        return hits.stream().map(RuleHit::rule).toList();
    }
}
