package com.fincore.ledger.persistence;

import com.fincore.ledger.domain.JournalType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Append-only. @Immutable stops Hibernate issuing UPDATEs; the database trigger stops everyone else. */
@Entity
@Immutable
@Table(name = "journal_transactions")
public class JournalEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "journal_type", nullable = false, length = 10)
    private JournalType journalType;
    @Column(nullable = false)
    private UUID reference;
    @Column(name = "reverses_journal_id")
    private UUID reversesJournalId;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(length = 200)
    private String description;
    @Column(name = "posted_at", nullable = false)
    private Instant postedAt = Instant.now();

    protected JournalEntity() {}

    public JournalEntity(JournalType type, UUID reference, UUID reversesJournalId, String currency, String description) {
        this.journalType = type;
        this.reference = reference;
        this.reversesJournalId = reversesJournalId;
        this.currency = currency;
        this.description = description;
    }

    public UUID getId() { return id; }
    public JournalType getJournalType() { return journalType; }
    public UUID getReference() { return reference; }
    public UUID getReversesJournalId() { return reversesJournalId; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public Instant getPostedAt() { return postedAt; }
}
