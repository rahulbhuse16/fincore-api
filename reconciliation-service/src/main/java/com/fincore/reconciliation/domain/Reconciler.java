package com.fincore.reconciliation.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Compares what the platform expects to have settled with what the settlement file says. Pure and deterministic:
 * the same inputs always yield the same, identically ordered exceptions, which is what makes a run repeatable.
 * Each (type, transfer) pair is reported at most once.
 */
public final class Reconciler {
    private Reconciler() {}

    static String expectedSettlementStatus(String expectedStatus) {
        return "REVERSED".equals(expectedStatus) ? "REVERSED" : "SETTLED";
    }

    public static ReconResult reconcile(List<ExpectedTransaction> expected, List<SettlementLine> settlements) {
        Map<UUID, List<SettlementLine>> byRef = new HashMap<>();
        for (SettlementLine line : settlements) {
            byRef.computeIfAbsent(line.transferRef(), k -> new ArrayList<>()).add(line);
        }
        Map<UUID, ExpectedTransaction> expectedByRef = new HashMap<>();
        expected.forEach(e -> expectedByRef.put(e.transferRef(), e));

        List<ReconException> exceptions = new ArrayList<>();
        int matched = 0;

        for (ExpectedTransaction exp : expected) {
            List<SettlementLine> lines = byRef.get(exp.transferRef());
            if (lines == null) {
                exceptions.add(new ReconException(ExceptionType.MISSING_SETTLEMENT, exp.transferRef(),
                        "expected " + exp.status() + " " + exp.currency() + " " + exp.amount().toPlainString()));
                continue;
            }
            lines.sort(Comparator.comparingInt(SettlementLine::lineNo));
            SettlementLine first = lines.get(0);
            boolean clean = true;
            if (lines.size() > 1) {
                clean = false;
                exceptions.add(new ReconException(ExceptionType.DUPLICATE_SETTLEMENT, exp.transferRef(),
                        lines.size() + " settlement lines (" + lines.stream().map(SettlementLine::settlementRef).toList() + ")"));
            }
            if (exp.amount().compareTo(first.amount()) != 0 || !exp.currency().equals(first.currency())) {
                clean = false;
                exceptions.add(new ReconException(ExceptionType.AMOUNT_MISMATCH, exp.transferRef(),
                        "expected " + exp.currency() + " " + exp.amount().toPlainString()
                                + " but settlement has " + first.currency() + " " + first.amount().toPlainString()));
            }
            if (!expectedSettlementStatus(exp.status()).equals(first.status())) {
                clean = false;
                exceptions.add(new ReconException(ExceptionType.STATUS_MISMATCH, exp.transferRef(),
                        "expected settlement status " + expectedSettlementStatus(exp.status()) + " but was " + first.status()));
            }
            if (clean) matched++;
        }

        for (UUID ref : byRef.keySet()) {
            if (!expectedByRef.containsKey(ref)) {
                exceptions.add(new ReconException(ExceptionType.UNEXPECTED_SETTLEMENT, ref,
                        byRef.get(ref).size() + " settlement line(s) for a transfer that was not completed"));
            }
        }
        exceptions.sort(Comparator.comparing(ReconException::type).thenComparing(e -> e.transferRef().toString()));
        return new ReconResult(matched, List.copyOf(exceptions));
    }
}
