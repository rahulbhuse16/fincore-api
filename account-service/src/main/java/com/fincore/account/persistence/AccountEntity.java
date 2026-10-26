package com.fincore.account.persistence;

import com.fincore.account.domain.AccountStatus;
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
@Table(name = "accounts")
public class AccountEntity {
    @Id
    private UUID id;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "account_number", nullable = false, unique = true, length = 12)
    private String accountNumber;
    @Column(nullable = false, length = 3)
    private String currency;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;
    @Column(name = "per_transfer_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal perTransferLimit;
    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyLimit;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AccountEntity() {}

    public AccountEntity(UUID id, UUID customerId, String accountNumber, String currency, BigDecimal perTransferLimit, BigDecimal dailyLimit) {
        this.id = id;
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.perTransferLimit = perTransferLimit;
        this.dailyLimit = dailyLimit;
    }

    public void changeStatus(AccountStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
    }

    public void changeLimits(BigDecimal perTransfer, BigDecimal daily) {
        this.perTransferLimit = perTransfer;
        this.dailyLimit = daily;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public String getAccountNumber() { return accountNumber; }
    public String getCurrency() { return currency; }
    public AccountStatus getStatus() { return status; }
    public BigDecimal getPerTransferLimit() { return perTransferLimit; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public Instant getCreatedAt() { return createdAt; }
}
