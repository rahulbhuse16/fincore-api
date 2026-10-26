package com.fincore.reconciliation.persistence;

import com.fincore.reconciliation.domain.ExceptionType;
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

@Entity
@Immutable
@Table(name = "reconciliation_exceptions")
public class ExceptionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "run_id", nullable = false)
    private UUID runId;
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 30)
    private ExceptionType type;
    @Column(name = "transfer_ref", nullable = false)
    private UUID transferRef;
    @Column(nullable = false, length = 500)
    private String details;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ExceptionEntity() {}

    public ExceptionEntity(UUID runId, ExceptionType type, UUID transferRef, String details) {
        this.runId = runId;
        this.type = type;
        this.transferRef = transferRef;
        this.details = details.substring(0, Math.min(details.length(), 500));
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public ExceptionType getType() { return type; }
    public UUID getTransferRef() { return transferRef; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
