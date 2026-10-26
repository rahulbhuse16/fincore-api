package com.fincore.platform.events;

import com.fincore.platform.contracts.Events;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Needs only Jackson + JUnit (no Spring), so scripts/verify-domain.sh runs it too. */
class EventCodecTest {

    private Events.TransferOutcome outcome(String amount) {
        return new Events.TransferOutcome(UUID.randomUUID(), "COMPLETED", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount), "INR", "rent", null, Instant.parse("2026-10-19T10:00:00Z"));
    }

    @Test
    void moneyRoundTripsWithoutLosingPrecision() {
        Events.TransferOutcome in = outcome("9007199254740993.01"); // not representable as a double
        EventEnvelope env = EventCodec.envelope("transfer.completed", "ref", "corr-12345", in);
        EventEnvelope parsed = EventCodec.parse(EventCodec.toJson(env));
        Events.TransferOutcome out = EventCodec.payload(parsed, Events.TransferOutcome.class);
        assertEquals(0, in.amount().compareTo(out.amount()));
        assertEquals("9007199254740993.01", out.amount().toPlainString());
        assertEquals(in, out);
    }

    @Test
    void envelopeCarriesIdsAndCorrelation() {
        EventEnvelope env = EventCodec.envelope("transfer.completed", "agg-1", "corr-12345", outcome("10.00"));
        EventEnvelope parsed = EventCodec.parse(EventCodec.toJson(env));
        assertEquals(env.eventId(), parsed.eventId());
        assertEquals("corr-12345", parsed.correlationId());
        assertEquals("transfer.completed", parsed.eventType());
        assertEquals(1, parsed.schemaVersion());
    }

    @Test
    void eachEnvelopeGetsAFreshEventId() {
        EventEnvelope a = EventCodec.envelope("t", "k", "c", new Events.FundsReserved(UUID.randomUUID()));
        EventEnvelope b = EventCodec.envelope("t", "k", "c", new Events.FundsReserved(UUID.randomUUID()));
        assertNotEquals(a.eventId(), b.eventId());
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        UUID ref = UUID.randomUUID();
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"ledger.funds.reserved\",\"aggregateId\":\"x\","
                + "\"correlationId\":\"c\",\"occurredAt\":\"2026-10-19T10:00:00Z\",\"schemaVersion\":2,"
                + "\"payload\":{\"transferRef\":\"" + ref + "\",\"brandNewField\":42},\"extraEnvelopeField\":true}";
        EventEnvelope env = EventCodec.parse(json);
        assertEquals(ref, EventCodec.payload(env, Events.FundsReserved.class).transferRef());
    }

    @Test
    void malformedAndIncompleteMessagesAreInvalidEvents() {
        assertThrows(InvalidEventException.class, () -> EventCodec.parse("not json"));
        assertThrows(InvalidEventException.class, () -> EventCodec.parse(""));
        assertThrows(InvalidEventException.class, () -> EventCodec.parse(null));
        assertThrows(InvalidEventException.class, () -> EventCodec.parse("{\"eventType\":\"x\",\"payload\":{}}")); // no eventId
    }

    @Test
    void payloadOfTheWrongShapeIsAnInvalidEvent() {
        EventEnvelope env = EventCodec.envelope("t", "k", "c", java.util.Map.of("transferRef", "not-a-uuid"));
        assertThrows(InvalidEventException.class, () -> EventCodec.payload(env, Events.FundsReserved.class));
    }
}
