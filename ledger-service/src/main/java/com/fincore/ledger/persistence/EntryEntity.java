package com.fincore.ledger.persistence;

import com.fincore.ledger.domain.Direction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class EntryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "journal_id", nullable = false)
    private UUID journalId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "ledger_balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal ledgerBalanceAfter;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EntryEntity() {}

    public EntryEntity(UUID journalId, UUID accountId, Direction direction, BigDecimal amount, String currency,
                       BigDecimal ledgerBalanceAfter) {
        this.journalId = journalId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.ledgerBalanceAfter = ledgerBalanceAfter;
    }

    public UUID getId() { return id; }
    public UUID getJournalId() { return journalId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getLedgerBalanceAfter() { return ledgerBalanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
