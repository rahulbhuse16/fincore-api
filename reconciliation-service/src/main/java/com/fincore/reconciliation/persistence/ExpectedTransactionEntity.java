package com.fincore.reconciliation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "expected_transactions")
public class ExpectedTransactionEntity {
    @Id
    @Column(name = "transfer_ref")
    private UUID transferRef;
    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ExpectedTransactionEntity() {}

    public ExpectedTransactionEntity(UUID transferRef, LocalDate businessDate, BigDecimal amount, String currency, String status) {
        this.transferRef = transferRef;
        this.businessDate = businessDate;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public void markReversed() {
        this.status = "REVERSED";
        this.updatedAt = Instant.now();
    }

    public UUID getTransferRef() { return transferRef; }
    public LocalDate getBusinessDate() { return businessDate; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
}
