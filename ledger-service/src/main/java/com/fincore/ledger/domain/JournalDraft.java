package com.fincore.ledger.domain;

import java.util.List;
import java.util.UUID;

/**
 * A journal transaction before posting. The constructor enforces the double-entry invariant:
 * at least two lines, one currency, and total debits == total credits. An unbalanced draft cannot exist.
 */
public record JournalDraft(JournalType type, UUID reference, String currency, List<JournalLine> lines,
                           UUID reversesJournalId, String description) {

    public JournalDraft {
        if (type == null || reference == null || currency == null || lines == null) {
            throw new IllegalArgumentException("type, reference, currency and lines are required");
        }
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a journal needs at least two lines");
        }
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (JournalLine line : lines) {
            if (!line.amount().currency().equals(currency)) {
                throw new IllegalArgumentException("mixed currencies in one journal");
            }
            if (line.direction() == Direction.DEBIT) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("journal is unbalanced: debits=" + debits.amount() + " credits=" + credits.amount());
        }
        if (type == JournalType.REVERSAL && reversesJournalId == null) {
            throw new IllegalArgumentException("a reversal must reference the journal it reverses");
        }
    }

    /** Customer-to-customer transfer: DEBIT source, CREDIT destination. */
    public static JournalDraft transfer(UUID transferRef, UUID source, UUID destination, Money amount) {
        if (source.equals(destination)) {
            throw new IllegalArgumentException("source and destination must differ");
        }
        return new JournalDraft(JournalType.TRANSFER, transferRef, amount.currency(),
                List.of(new JournalLine(source, Direction.DEBIT, amount),
                        new JournalLine(destination, Direction.CREDIT, amount)),
                null, "Transfer " + transferRef);
    }

    /** Simulated cash-in: DEBIT the system cash account (asset up), CREDIT the customer (liability up). */
    public static JournalDraft deposit(UUID reference, UUID cashAccount, UUID customerAccount, Money amount) {
        return new JournalDraft(JournalType.DEPOSIT, reference, amount.currency(),
                List.of(new JournalLine(cashAccount, Direction.DEBIT, amount),
                        new JournalLine(customerAccount, Direction.CREDIT, amount)),
                null, "Deposit " + reference);
    }

    /**
     * Compensating journal: every line of the original with its direction flipped. History is never edited or
     * deleted; the original journal stays exactly as posted and the reversal is a new, linked journal.
     */
    public static JournalDraft reversalOf(UUID originalJournalId, UUID reference, String currency, List<JournalLine> originalLines) {
        return new JournalDraft(JournalType.REVERSAL, reference, currency,
                originalLines.stream().map(JournalLine::opposite).toList(), originalJournalId,
                "Reversal of " + originalJournalId);
    }
}
