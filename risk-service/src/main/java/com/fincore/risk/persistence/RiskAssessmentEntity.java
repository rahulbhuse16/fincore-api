package com.fincore.risk.persistence;

import com.fincore.risk.domain.Decision;
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

/** Immutable audit record of a risk decision. */
@Entity
@Immutable
@Table(name = "risk_assessments")
public class RiskAssessmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "reference_id", nullable = false, unique = true)
    private UUID referenceId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
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
    private Decision decision;
    @Column(nullable = false)
    private int score;
    @Column(nullable = false, length = 500)
    private String reasons;
    @Column(name = "rules_version", nullable = false, length = 20)
    private String rulesVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RiskAssessmentEntity() {}

    public RiskAssessmentEntity(UUID referenceId, UUID customerId, UUID source, UUID destination, BigDecimal amount,
                                String currency, Decision decision, int score, String reasons, String rulesVersion) {
        this.referenceId = referenceId;
        this.customerId = customerId;
        this.sourceAccountId = source;
        this.destinationAccountId = destination;
        this.amount = amount;
        this.currency = currency;
        this.decision = decision;
        this.score = score;
        this.reasons = reasons;
        this.rulesVersion = rulesVersion;
    }

    public UUID getId() { return id; }
    public UUID getReferenceId() { return referenceId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Decision getDecision() { return decision; }
    public int getScore() { return score; }
    public String getReasons() { return reasons; }
    public String getRulesVersion() { return rulesVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
