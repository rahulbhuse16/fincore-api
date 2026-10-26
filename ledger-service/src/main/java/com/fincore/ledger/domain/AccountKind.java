package com.fincore.ledger.domain;

/**
 * Normal-balance side. Customer deposit accounts are LIABILITIES of the bank: a CREDIT increases the balance.
 * The system cash-clearing account is an ASSET: a DEBIT increases the balance.
 */
public enum AccountKind {
    LIABILITY(Direction.CREDIT),
    ASSET(Direction.DEBIT);

    private final Direction increasingSide;

    AccountKind(Direction increasingSide) {
        this.increasingSide = increasingSide;
    }

    public boolean increases(Direction direction) {
        return direction == increasingSide;
    }
}
