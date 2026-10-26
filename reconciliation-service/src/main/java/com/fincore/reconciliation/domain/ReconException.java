package com.fincore.reconciliation.domain;

import java.util.UUID;

public record ReconException(ExceptionType type, UUID transferRef, String details) {}
