package com.fincore.customer.persistence;

import com.fincore.customer.domain.KycStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class CustomerEntity {
    @Id
    private UUID id;
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(length = 20)
    private String phone;
    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;
    @Column(name = "document_type", nullable = false, length = 20)
    private String documentType;
    @Column(name = "document_last4", nullable = false, length = 4)
    private String documentLast4;
    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_status", nullable = false, length = 10)
    private KycStatus kycStatus;
    @Column(name = "kyc_reason", length = 80)
    private String kycReason;
    @Column(name = "kyc_updated_at", nullable = false)
    private Instant kycUpdatedAt = Instant.now();
    @Column(nullable = false, length = 10)
    private String status = "ACTIVE";
    @Version
    private Long version;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CustomerEntity() {}

    public CustomerEntity(UUID id, String fullName, String email, String phone, LocalDate dob, String documentType,
                          String documentLast4, KycStatus kycStatus, String kycReason) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.dateOfBirth = dob;
        this.documentType = documentType;
        this.documentLast4 = documentLast4;
        this.kycStatus = kycStatus;
        this.kycReason = kycReason;
    }

    public void changeKyc(KycStatus next, String reason) {
        this.kycStatus = next;
        this.kycReason = reason;
        this.kycUpdatedAt = Instant.now();
        this.updatedAt = kycUpdatedAt;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getDocumentType() { return documentType; }
    public String getDocumentLast4() { return documentLast4; }
    public KycStatus getKycStatus() { return kycStatus; }
    public String getKycReason() { return kycReason; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getKycUpdatedAt() { return kycUpdatedAt; }
}
