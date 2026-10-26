package com.fincore.transaction.persistence;

import com.fincore.transaction.domain.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
public class TransferEntity {
    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;
    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;
    @Column(name = "destination_customer_id", nullable = false)
    private UUID destinationCustomerId;
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(length = 140)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransferStatus status;
    @Column(name = "failure_reason", length = 80)
    private String failureReason;
    @Column(name = "risk_decision", length = 10)
    private String riskDecision;
    @Column(name = "risk_assessment_id")
    private UUID riskAssessmentId;
    @Column(name = "reversal_reason", length = 200)
    private String reversalReason;
    @Column(name = "reversal_failure_reason", length = 80)
    private String reversalFailureReason;
    @Column(name = "recovery_attempts", nullable = false)
    private int recoveryAttempts;
    @Column(name = "last_recovery_at")
    private Instant lastRecoveryAt;
    @Column(name = "needs_attention", nullable = false)
    private boolean needsAttention;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "reversed_at")
    private Instant reversedAt;

    protected TransferEntity() {}

    public TransferEntity(UUID id, UUID customerId, String idempotencyKey, String fingerprint, UUID source, UUID destination,
                          UUID destinationCustomerId, BigDecimal amount, String currency, String description,
                          TransferStatus status, String riskDecision, UUID riskAssessmentId, String failureReason) {
        this.id = id;
        this.customerId = customerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = fingerprint;
        this.sourceAccountId = source;
        this.destinationAccountId = destination;
        this.destinationCustomerId = destinationCustomerId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.status = status;
        this.riskDecision = riskDecision;
        this.riskAssessmentId = riskAssessmentId;
        this.failureReason = failureReason;
    }

    /** Single choke point for status changes: the state machine is enforced here. */
    public TransferStatus transitionTo(TransferStatus next) {
        TransferStatus previous = status;
        this.status = previous.requireTransitionTo(next);
        this.updatedAt = Instant.now();
        if (next == TransferStatus.COMPLETED && completedAt == null) completedAt = updatedAt;
        if (next == TransferStatus.REVERSED) reversedAt = updatedAt;
        return previous;
    }

    public void fail(String reason) { this.failureReason = reason; }
    public void setReversalReason(String r) { this.reversalReason = r; }
    public void setReversalFailureReason(String r) { this.reversalFailureReason = r; this.updatedAt = Instant.now(); }
    public void recordRecoveryAttempt(int maxAttempts) {
        this.recoveryAttempts++;
        this.lastRecoveryAt = Instant.now();
        if (recoveryAttempts >= maxAttempts) this.needsAttention = true;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public UUID getDestinationCustomerId() { return destinationCustomerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public TransferStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getRiskDecision() { return riskDecision; }
    public UUID getRiskAssessmentId() { return riskAssessmentId; }
    public String getReversalReason() { return reversalReason; }
    public String getReversalFailureReason() { return reversalFailureReason; }
    public int getRecoveryAttempts() { return recoveryAttempts; }
    public boolean isNeedsAttention() { return needsAttention; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getReversedAt() { return reversedAt; }
}
