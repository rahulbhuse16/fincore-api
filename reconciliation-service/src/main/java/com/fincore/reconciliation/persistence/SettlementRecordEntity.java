package com.fincore.reconciliation.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** Read-only view of ingested settlement lines (inserts go through SettlementIngestService with ON CONFLICT DO NOTHING). */
@Entity
@Table(name = "settlement_records")
public class SettlementRecordEntity {
    @Id
    private UUID id;
    @Column(name = "batch_id") private String batchId;
    @Column(name = "line_no") private int lineNo;
    @Column(name = "settlement_ref") private String settlementRef;
    @Column(name = "transfer_ref") private UUID transferRef;
    private BigDecimal amount;
    private String currency;
    private String status;

    protected SettlementRecordEntity() {}

    public String getBatchId() { return batchId; }
    public int getLineNo() { return lineNo; }
    public String getSettlementRef() { return settlementRef; }
    public UUID getTransferRef() { return transferRef; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
}
