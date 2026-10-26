package com.fincore.risk.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record RiskRequest(UUID referenceId, UUID customerId, UUID sourceAccountId, UUID destinationAccountId,
                          BigDecimal amount, String currency) {}
