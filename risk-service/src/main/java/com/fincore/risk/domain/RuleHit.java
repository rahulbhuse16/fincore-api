package com.fincore.risk.domain;

public record RuleHit(String rule, Decision decision, int weight, String message) {}
