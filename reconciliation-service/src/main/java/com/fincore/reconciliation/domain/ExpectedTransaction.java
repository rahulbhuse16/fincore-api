package com.fincore.reconciliation.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** status is COMPLETED or REVERSED as known to the transaction service. */
public record ExpectedTransaction(UUID transferRef, BigDecimal amount, String currency, String status) {}
