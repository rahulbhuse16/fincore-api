package com.fincore.ledger.persistence;

import com.fincore.ledger.domain.AccountBalances;
import com.fincore.ledger.domain.AccountKind;
import com.fincore.ledger.domain.AccountState;
import com.fincore.ledger.domain.AccountStatus;
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
@Table(name = "ledger_accounts")
public class LedgerAccountEntity {
    @Id
    private UUID id;
    @Column(name = "owner_customer_id")
    private UUID ownerCustomerId;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountKind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;
    @Column(name = "ledger_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal ledgerBalance = BigDecimal.ZERO;
    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance = BigDecimal.ZERO;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LedgerAccountEntity() {}

    public LedgerAccountEntity(UUID id, UUID ownerCustomerId, String currency, AccountKind kind, AccountStatus status) {
        this.id = id;
        this.ownerCustomerId = ownerCustomerId;
        this.currency = currency;
        this.kind = kind;
        this.status = status;
    }

    public AccountState toState() {
        return new AccountState(id, kind, status, currency, new AccountBalances(ledgerBalance, availableBalance));
    }

    public void applyBalances(AccountBalances b) {
        this.ledgerBalance = b.ledger();
        this.availableBalance = b.available();
        this.updatedAt = Instant.now();
    }

    public void changeStatus(AccountStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOwnerCustomerId() { return ownerCustomerId; }
    public String getCurrency() { return currency; }
    public AccountKind getKind() { return kind; }
    public AccountStatus getStatus() { return status; }
    public BigDecimal getLedgerBalance() { return ledgerBalance; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public Instant getUpdatedAt() { return updatedAt; }
}
