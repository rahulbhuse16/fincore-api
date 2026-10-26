package com.fincore.reconciliation.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** status is SETTLED or REVERSED as reported by the (synthetic) external settlement file. */
public record SettlementLine(String batchId, int lineNo, String settlementRef, UUID transferRef,
                             BigDecimal amount, String currency, String status) {}
