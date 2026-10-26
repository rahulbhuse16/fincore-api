package com.fincore.notification.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    boolean existsByEventIdAndRecipientCustomerIdAndChannel(UUID eventId, UUID recipient, String channel);

    Page<NotificationEntity> findByRecipientCustomerId(UUID recipient, Pageable pageable);
}
