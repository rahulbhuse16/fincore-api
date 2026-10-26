package com.fincore.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReconcilerTest {
    private static ExpectedTransaction exp(UUID ref, String amount, String status) {
        return new ExpectedTransaction(ref, new BigDecimal(amount), "INR", status);
    }

    private static SettlementLine line(UUID ref, int no, String amount, String status) {
        return new SettlementLine("B1", no, "S-" + no, ref, new BigDecimal(amount), "INR", status);
    }

    private static List<ExceptionType> types(ReconResult r) {
        return r.exceptions().stream().map(ReconException::type).toList();
    }

    @Test
    void perfectMatchHasNoExceptions() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(exp(a, "100.00", "COMPLETED"), exp(b, "50.00", "REVERSED")),
                List.of(line(a, 1, "100.0000", "SETTLED"), line(b, 2, "50", "REVERSED")));
        assertEquals(2, r.matched());
        assertTrue(r.exceptions().isEmpty());
    }

    @Test
    void missingSettlementIsReported() {
        UUID a = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(exp(a, "100", "COMPLETED")), List.of());
        assertEquals(List.of(ExceptionType.MISSING_SETTLEMENT), types(r));
        assertEquals(0, r.matched());
    }

    @Test
    void unexpectedSettlementIsReported() {
        UUID stranger = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(), List.of(line(stranger, 1, "10", "SETTLED")));
        assertEquals(List.of(ExceptionType.UNEXPECTED_SETTLEMENT), types(r));
        assertEquals(stranger, r.exceptions().get(0).transferRef());
    }

    @Test
    void duplicateSettlementLinesAreReportedOnce() {
        UUID a = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(exp(a, "100", "COMPLETED")),
                List.of(line(a, 1, "100", "SETTLED"), line(a, 2, "100", "SETTLED"), line(a, 3, "100", "SETTLED")));
        assertEquals(List.of(ExceptionType.DUPLICATE_SETTLEMENT), types(r));
        assertTrue(r.exceptions().get(0).details().startsWith("3 settlement lines"));
        assertEquals(0, r.matched());
    }

    @Test
    void amountMismatchIncludingTinyDifferencesAndCurrency() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(
                List.of(exp(a, "100.00", "COMPLETED"), exp(b, "100.00", "COMPLETED")),
                List.of(line(a, 1, "100.01", "SETTLED"),
                        new SettlementLine("B1", 2, "S-2", b, new BigDecimal("100.00"), "USD", "SETTLED")));
        assertEquals(List.of(ExceptionType.AMOUNT_MISMATCH, ExceptionType.AMOUNT_MISMATCH), types(r));
    }

    @Test
    void statusMismatchWhenReversedLocallyButStillSettledExternally() {
        UUID a = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(exp(a, "100", "REVERSED")), List.of(line(a, 1, "100", "SETTLED")));
        assertEquals(List.of(ExceptionType.STATUS_MISMATCH), types(r));
    }

    @Test
    void severalProblemsOnOneTransferAreAllReported() {
        UUID a = UUID.randomUUID();
        ReconResult r = Reconciler.reconcile(List.of(exp(a, "100", "COMPLETED")),
                List.of(line(a, 1, "90", "REVERSED"), line(a, 2, "90", "REVERSED")));
        assertEquals(List.of(ExceptionType.DUPLICATE_SETTLEMENT, ExceptionType.AMOUNT_MISMATCH, ExceptionType.STATUS_MISMATCH), types(r));
    }

    @Test
    void resultIsDeterministicRegardlessOfInputOrder() {
        List<ExpectedTransaction> expected = new ArrayList<>();
        List<SettlementLine> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID ref = UUID.randomUUID();
            expected.add(exp(ref, "10", "COMPLETED"));
            if (i % 3 != 0) lines.add(line(ref, i, i % 2 == 0 ? "10" : "11", "SETTLED"));
        }
        ReconResult first = Reconciler.reconcile(expected, new ArrayList<>(lines));
        java.util.Collections.reverse(expected);
        java.util.Collections.reverse(lines);
        ReconResult second = Reconciler.reconcile(expected, new ArrayList<>(lines));
        assertEquals(first, second);
    }
}
