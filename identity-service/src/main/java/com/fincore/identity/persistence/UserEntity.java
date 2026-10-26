package com.fincore.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
    public enum Role { CUSTOMER, ADMIN }

    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 50)
    private String username;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;
    @Column(name = "customer_id", unique = true)
    private UUID customerId;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserEntity() {}

    public UserEntity(UUID id, String username, String email, String passwordHash, Role role, UUID customerId) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.customerId = customerId;
    }

    public void setLockState(int failedAttempts, Instant lockedUntil) {
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public UUID getCustomerId() { return customerId; }
    public boolean isEnabled() { return enabled; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
}
