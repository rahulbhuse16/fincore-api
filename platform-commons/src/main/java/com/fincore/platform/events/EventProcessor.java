package com.fincore.platform.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Idempotent consumer. The "already processed" marker is written in the SAME database transaction as the business
 * effect, so either both commit or neither does. Duplicate deliveries (broker redelivery, relay crash after publish,
 * consumer crash before offset commit) are therefore harmless.
 */
public class EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public EventProcessor(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @Transactional
    public void process(String consumerName, String rawJson, Consumer<EventEnvelope> handler) {
        EventEnvelope event = EventCodec.parse(rawJson); // InvalidEventException -> non-retryable -> DLT
        MDC.put("correlationId", event.correlationId() == null ? UUID.randomUUID().toString() : event.correlationId());
        try {
            if (!markProcessed(event.eventId(), consumerName)) {
                meters.counter("fincore.events.duplicates", "consumer", consumerName, "type", event.eventType()).increment();
                log.info("Skipping duplicate event {} type={}", event.eventId(), event.eventType());
                return;
            }
            handler.accept(event);
            meters.counter("fincore.events.processed", "consumer", consumerName, "type", event.eventType()).increment();
        } finally {
            MDC.remove("correlationId");
        }
    }

    boolean markProcessed(UUID eventId, String consumer) {
        return jdbc.update("INSERT INTO processed_events(event_id, consumer) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, consumer) == 1;
    }
}
