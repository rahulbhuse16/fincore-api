package com.fincore.reconciliation.domain;

import java.util.List;

public record ReconResult(int matched, List<ReconException> exceptions) {}
