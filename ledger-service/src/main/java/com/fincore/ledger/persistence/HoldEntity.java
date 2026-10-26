package com.fincore.ledger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_holds")
public class HoldEntity {
    public enum Status { HELD, POSTED, RELEASED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "transfer_ref", nullable = false, unique = true)
    private UUID transferRef;
    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;
    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;
    @Column(name = "reason_code", length = 60)
    private String reasonCode;
    @Column(name = "journal_id")
    private UUID journalId;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected HoldEntity() {}

    public HoldEntity(UUID transferRef, UUID source, UUID destination, BigDecimal amount, String currency,
                      Status status, String reasonCode) {
        this.transferRef = transferRef;
        this.sourceAccountId = source;
        this.destinationAccountId = destination;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public void markPosted(UUID journalId) {
        this.status = Status.POSTED;
        this.journalId = journalId;
        this.updatedAt = Instant.now();
    }

    public void markReleased(String reason) {
        this.status = Status.RELEASED;
        this.reasonCode = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTransferRef() { return transferRef; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public String getReasonCode() { return reasonCode; }
    public UUID getJournalId() { return journalId; }
}
