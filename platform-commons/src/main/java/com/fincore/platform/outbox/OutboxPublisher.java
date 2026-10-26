package com.fincore.platform.outbox;

import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business code calls publish() INSIDE its own transaction. Propagation.MANDATORY makes it impossible to call this
 * without an active transaction, which is what guarantees "state change and event commit atomically".
 */
public class OutboxPublisher {
    private final OutboxStore store;

    public OutboxPublisher(OutboxStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String topic, String key, Object payload) {
        String correlationId = MDC.get("correlationId");
        EventEnvelope envelope = EventCodec.envelope(topic, key, correlationId != null ? correlationId : UUID.randomUUID().toString(), payload);
        store.insert(envelope.eventId(), topic, key, EventCodec.toJson(envelope));
        return envelope.eventId();
    }
}
