package com.fincore.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_records")
public class NotificationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "transfer_ref", nullable = false)
    private UUID transferRef;
    @Column(name = "recipient_customer_id", nullable = false)
    private UUID recipientCustomerId;
    @Column(nullable = false, length = 10)
    private String channel = "EMAIL";
    @Column(nullable = false, length = 40)
    private String template;
    @Column(nullable = false, length = 120)
    private String subject;
    @Column(nullable = false, length = 500)
    private String body;
    @Column(nullable = false, length = 10)
    private String status = "SENT";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NotificationEntity() {}

    public NotificationEntity(UUID eventId, UUID transferRef, UUID recipient, String template, String subject, String body) {
        this.eventId = eventId;
        this.transferRef = transferRef;
        this.recipientCustomerId = recipient;
        this.template = template;
        this.subject = subject;
        this.body = body.substring(0, Math.min(body.length(), 500));
    }

    public UUID getId() { return id; }
    public UUID getTransferRef() { return transferRef; }
    public String getTemplate() { return template; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
