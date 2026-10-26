package com.fincore.transaction.domain;

public class TransferRuleViolation extends RuntimeException {
    private final String code;

    public TransferRuleViolation(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
