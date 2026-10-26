package com.fincore.platform.events;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.UUID;

/**
 * Serialises envelopes. BigDecimal handling is deliberate: USE_BIG_DECIMAL_FOR_FLOATS prevents money from being
 * parsed as a double, and WRITE_BIGDECIMAL_AS_PLAIN prevents exponent notation.
 */
public final class EventCodec {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private EventCodec() {}

    public static EventEnvelope envelope(String eventType, String aggregateId, String correlationId, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, aggregateId, correlationId, Instant.now(),
                EventEnvelope.CURRENT_SCHEMA_VERSION, MAPPER.valueToTree(payload));
    }

    public static String toJson(EventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + envelope.eventId(), e);
        }
    }

    public static EventEnvelope parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty event");
        }
        try {
            EventEnvelope e = MAPPER.readValue(json, EventEnvelope.class);
            if (e.eventId() == null || e.eventType() == null || e.payload() == null) {
                throw new InvalidEventException("Envelope is missing eventId, eventType or payload");
            }
            return e;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed event JSON", e);
        }
    }

    public static <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return MAPPER.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Payload does not match " + type.getSimpleName(), e);
        }
    }
}
