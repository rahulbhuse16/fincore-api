package com.fincore.platform.outbox;

import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polling relay: reads unpublished outbox rows in sequence order and publishes them to Kafka.
 *
 * Delivery is AT-LEAST-ONCE: if the process dies after Kafka acknowledged a message but before the row is marked
 * published, the row is published again after restart. Consumers deduplicate on eventId (see EventProcessor).
 * If Kafka is down the rows simply stay in the table; nothing is lost and nothing is reported as sent.
 * The design is CDC-ready: a Debezium outbox connector can replace this poller without schema changes.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       MeterRegistry meters, int batchSize, long sendTimeoutMs) {
        this.store = store;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.published = meters.counter("fincore.outbox.published");
        this.failures = meters.counter("fincore.outbox.publish.failures");
        Gauge.builder("fincore.outbox.pending", store, OutboxStore::pendingCount).register(meters);
    }

    @Scheduled(fixedDelayString = "${fincore.outbox.poll-interval-ms:500}")
    public void poll() {
        int handled;
        do {
            Integer n = tx.execute(status -> publishBatch());
            handled = n == null ? 0 : n;
        } while (handled == batchSize);
    }

    /** Publishes one batch; stops at the first failure so per-key ordering is preserved. */
    int publishBatch() {
        List<OutboxStore.Row> rows = store.lockBatch(batchSize);
        int done = 0;
        for (OutboxStore.Row row : rows) {
            try {
                EventEnvelope envelope = EventCodec.parse(row.payload());
                ProducerRecord<String, String> record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
                record.headers().add("eventId", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
                if (envelope.correlationId() != null) {
                    record.headers().add("correlationId", envelope.correlationId().getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                store.markPublished(row.seq());
                published.increment();
                done++;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.increment();
                log.warn("Outbox publish failed for event {} (will retry): {}", row.eventId(), e.toString());
                store.recordFailure(row.seq(), e.toString());
                return done; // commit what succeeded, keep the rest for the next poll
            }
        }
        return done;
    }
}
