package com.fincore.platform.outbox;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the transactional-outbox guarantees (requires Docker):
 *  - a rolled-back transaction publishes nothing;
 *  - when Kafka is unreachable the event stays in the outbox (nothing lost, nothing falsely "sent");
 *  - once Kafka is reachable the SAME row is published;
 *  - "crash after publish, before marking published" yields a duplicate delivery, which is why consumers dedupe.
 * Lives in package com.fincore.platform.outbox to reach the package-private publishBatch().
 */
@SpringBootTest(properties = {"fincore.security.jwt-secret=integration-test-secret-integration-test-secret",
        "fincore.outbox.poll-interval-ms=3600000"})
@Testcontainers
class OutboxRelayIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxStore store;
    @Autowired PlatformTransactionManager tm;
    @Autowired JdbcTemplate jdbc;

    private KafkaTemplate<String, String> template(String bootstrap) {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                "bootstrap.servers", bootstrap, "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class,
                "max.block.ms", 1500, "request.timeout.ms", 1500, "delivery.timeout.ms", 3000)));
    }

    private OutboxRelay relay(KafkaTemplate<String, String> t) {
        return new OutboxRelay(store, t, new TransactionTemplate(tm), new SimpleMeterRegistry(), 10, 2000);
    }

    private List<ConsumerRecord<String, String>> consume(String key) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.LEDGER_FUNDS_RESERVED));
            List<ConsumerRecord<String, String>> out = new java.util.ArrayList<>();
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                c.poll(Duration.ofMillis(500)).forEach(r -> { if (key.equals(r.key())) out.add(r); });
                if (!out.isEmpty()) break;
            }
            return out;
        }
    }

    private void publishInTx(String key) {
        new TransactionTemplate(tm).executeWithoutResult(s ->
                publisher.publish(Topics.LEDGER_FUNDS_RESERVED, key, new Events.FundsReserved(UUID.fromString(key))));
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        String key = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(tm).executeWithoutResult(s -> {
            publisher.publish(Topics.LEDGER_FUNDS_RESERVED, key, new Events.FundsReserved(UUID.fromString(key)));
            throw new IllegalStateException("business failure after publish()");
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where event_key = ?", Integer.class, key));
    }

    @Test
    void publishingWithoutATransactionIsImpossible() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> publisher.publish(Topics.LEDGER_FUNDS_RESERVED, "k", new Events.FundsReserved(UUID.randomUUID())));
    }

    @Test
    void brokerOutageKeepsTheEventPendingAndRecoveryPublishesIt() {
        String key = UUID.randomUUID().toString();
        publishInTx(key);

        OutboxRelay deadBroker = relay(template("localhost:1")); // nothing listens here
        assertEquals(0, deadBroker.publishBatch());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where event_key = ? and published_at is null", Integer.class, key),
                "event must stay pending while the broker is unavailable");
        assertTrue(jdbc.queryForObject("select attempts from outbox_events where event_key = ?", Integer.class, key) >= 1);

        OutboxRelay healthy = relay(template(kafka.getBootstrapServers()));
        new TransactionTemplate(tm).executeWithoutResult(s -> healthy.publishBatch());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where event_key = ? and published_at is null", Integer.class, key));
        List<ConsumerRecord<String, String>> received = consume(key);
        assertEquals(1, received.size());
        assertEquals(key, EventCodec.parse(received.get(0).value()).aggregateId());
    }

    @Test
    void crashBetweenPublishAndMarkingPublishedCausesARedeliveryNotALoss() {
        String key = UUID.randomUUID().toString();
        publishInTx(key);
        OutboxRelay healthy = relay(template(kafka.getBootstrapServers()));
        new TransactionTemplate(tm).executeWithoutResult(s -> healthy.publishBatch());
        // Simulate the crash: the row was sent but "published_at" was never committed.
        jdbc.update("update outbox_events set published_at = null where event_key = ?", key);
        new TransactionTemplate(tm).executeWithoutResult(s -> healthy.publishBatch());
        assertEquals(2, countMessages(key), "at-least-once: the same event id is delivered twice; consumers must dedupe");
    }

    private int countMessages(String key) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-count-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.LEDGER_FUNDS_RESERVED));
            int n = 0;
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) if (key.equals(r.key())) n++;
                if (n >= 2) break;
            }
            return n;
        }
    }
}
