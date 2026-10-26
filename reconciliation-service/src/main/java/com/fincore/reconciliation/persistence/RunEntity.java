package com.fincore.reconciliation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs")
public class RunEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;
    @Column(name = "batch_id", nullable = false, length = 40)
    private String batchId;
    @Column(name = "expected_count", nullable = false)
    private int expectedCount;
    @Column(name = "settlement_count", nullable = false)
    private int settlementCount;
    @Column(name = "matched_count", nullable = false)
    private int matchedCount;
    @Column(name = "exception_count", nullable = false)
    private int exceptionCount;
    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RunEntity() {}

    public RunEntity(LocalDate businessDate, String batchId, int expected, int settlements, int matched, int exceptions, String createdBy) {
        this.businessDate = businessDate;
        this.batchId = batchId;
        this.expectedCount = expected;
        this.settlementCount = settlements;
        this.matchedCount = matched;
        this.exceptionCount = exceptions;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public LocalDate getBusinessDate() { return businessDate; }
    public String getBatchId() { return batchId; }
    public int getExpectedCount() { return expectedCount; }
    public int getSettlementCount() { return settlementCount; }
    public int getMatchedCount() { return matchedCount; }
    public int getExceptionCount() { return exceptionCount; }
    public Instant getCreatedAt() { return createdAt; }
}
