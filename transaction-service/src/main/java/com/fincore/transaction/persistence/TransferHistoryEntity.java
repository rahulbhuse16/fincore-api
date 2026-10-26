package com.fincore.transaction.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_state_history")
public class TransferHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;
    @Column(name = "from_status", length = 16)
    private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 16)
    private String toStatus;
    @Column(length = 200)
    private String reason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected TransferHistoryEntity() {}

    public TransferHistoryEntity(UUID transferId, String from, String to, String reason) {
        this.transferId = transferId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
    }
}
