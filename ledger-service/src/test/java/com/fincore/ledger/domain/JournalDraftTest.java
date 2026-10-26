package com.fincore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JournalDraftTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final Money rs1000 = Money.of("1000", "INR");

    @Test
    void transferProducesBalancedDebitAndCredit() {
        JournalDraft d = JournalDraft.transfer(UUID.randomUUID(), a, b, rs1000);
        assertEquals(2, d.lines().size());
        assertEquals(Direction.DEBIT, d.lines().get(0).direction());
        assertEquals(a, d.lines().get(0).accountId());
        assertEquals(Direction.CREDIT, d.lines().get(1).direction());
        assertEquals(b, d.lines().get(1).accountId());
    }

    @Test
    void unbalancedJournalCannotBeConstructed() {
        List<JournalLine> lines = List.of(
                new JournalLine(a, Direction.DEBIT, rs1000),
                new JournalLine(b, Direction.CREDIT, Money.of("999.99", "INR")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new JournalDraft(JournalType.TRANSFER, UUID.randomUUID(), "INR", lines, null, "x"));
        assertTrue(e.getMessage().contains("unbalanced"));
    }

    @Test
    void singleLineJournalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalDraft(JournalType.TRANSFER, UUID.randomUUID(), "INR",
                List.of(new JournalLine(a, Direction.DEBIT, rs1000)), null, "x"));
    }

    @Test
    void mixedCurrenciesAreRejected() {
        List<JournalLine> lines = List.of(
                new JournalLine(a, Direction.DEBIT, rs1000),
                new JournalLine(b, Direction.CREDIT, Money.of("1000", "USD")));
        assertThrows(IllegalArgumentException.class,
                () -> new JournalDraft(JournalType.TRANSFER, UUID.randomUUID(), "INR", lines, null, "x"));
    }

    @Test
    void zeroAndNegativeLinesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalLine(a, Direction.DEBIT, Money.zero("INR")));
        assertThrows(IllegalArgumentException.class, () -> new JournalLine(a, Direction.DEBIT, Money.of("-1", "INR")));
    }

    @Test
    void transferToSelfIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JournalDraft.transfer(UUID.randomUUID(), a, a, rs1000));
    }

    @Test
    void reversalFlipsEveryLineAndLinksToTheOriginal() {
        JournalDraft original = JournalDraft.transfer(UUID.randomUUID(), a, b, rs1000);
        UUID originalId = UUID.randomUUID();
        JournalDraft reversal = JournalDraft.reversalOf(originalId, UUID.randomUUID(), "INR", original.lines());
        assertEquals(JournalType.REVERSAL, reversal.type());
        assertEquals(originalId, reversal.reversesJournalId());
        assertEquals(Direction.CREDIT, reversal.lines().get(0).direction()); // source gets its money back
        assertEquals(a, reversal.lines().get(0).accountId());
        assertEquals(Direction.DEBIT, reversal.lines().get(1).direction());
        assertEquals(b, reversal.lines().get(1).accountId());
    }

    @Test
    void reversalWithoutOriginalReferenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalDraft(JournalType.REVERSAL, UUID.randomUUID(), "INR",
                JournalDraft.transfer(UUID.randomUUID(), a, b, rs1000).lines(), null, "x"));
    }
}
