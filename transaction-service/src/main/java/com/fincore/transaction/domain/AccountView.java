package com.fincore.transaction.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** What the transaction service needs to know about an account (owned by account-service). */
public record AccountView(UUID accountId, UUID customerId, String currency, String status,
                          BigDecimal perTransferLimit, BigDecimal dailyLimit) {}
