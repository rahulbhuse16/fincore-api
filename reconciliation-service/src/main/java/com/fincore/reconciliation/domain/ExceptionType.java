package com.fincore.reconciliation.domain;

public enum ExceptionType {
    MISSING_SETTLEMENT,      // we expected the transfer to settle, the settlement file has no line for it
    UNEXPECTED_SETTLEMENT,   // the settlement file has a line for a transfer we never completed
    DUPLICATE_SETTLEMENT,    // more than one settlement line for the same transfer
    AMOUNT_MISMATCH,         // amount or currency differs
    STATUS_MISMATCH          // e.g. we reversed it but the settlement still says SETTLED
}
