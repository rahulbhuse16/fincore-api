package com.fincore.notification.application;

import com.fincore.notification.domain.MessageBuilder;
import com.fincore.notification.persistence.NotificationEntity;
import com.fincore.notification.persistence.NotificationRepository;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import com.fincore.platform.events.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Runs inside the consumer transaction (see NotificationListener). If sending throws, the whole transaction -
 * including the processed-event marker - rolls back, the record is retried with backoff and finally dead-lettered.
 * The transfer itself is unaffected: it completed long before, in a different service and database.
 */
@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final NotificationSender sender;
    private final MeterRegistry meters;

    public NotificationService(NotificationRepository repository, NotificationSender sender, MeterRegistry meters) {
        this.repository = repository;
        this.sender = sender;
        this.meters = meters;
    }

    @Transactional
    public void handle(EventEnvelope event) {
        Events.TransferOutcome o = EventCodec.payload(event, Events.TransferOutcome.class);
        java.util.List<MessageBuilder.Role> roles;
        try {
            roles = MessageBuilder.recipientsFor(event.eventType());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException(e.getMessage(), e);
        }
        for (MessageBuilder.Role role : roles) {
            UUID recipient = role == MessageBuilder.Role.SENDER ? o.sourceCustomerId() : o.destinationCustomerId();
            if (recipient == null || repository.existsByEventIdAndRecipientCustomerIdAndChannel(event.eventId(), recipient, "EMAIL")) {
                continue;
            }
            MessageBuilder.Message message = MessageBuilder.build(event.eventType(), role, o.amount(), o.currency(),
                    o.description(), o.failureReason());
            sender.send(recipient, message, o.description());
            repository.save(new NotificationEntity(event.eventId(), o.transferRef(), recipient, message.template(),
                    message.subject(), message.body()));
            meters.counter("fincore.notifications.sent", "template", message.template()).increment();
        }
    }
}
