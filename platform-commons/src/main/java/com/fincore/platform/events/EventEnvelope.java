package com.fincore.platform.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Wire format of every Kafka message. eventId is the deduplication key for consumers. */
public record EventEnvelope(UUID eventId, String eventType, String aggregateId, String correlationId,
                            Instant occurredAt, int schemaVersion, JsonNode payload) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
