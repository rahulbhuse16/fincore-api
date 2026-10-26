package com.fincore.notification;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-side guarantees (requires Docker): idempotent handling of duplicate events, retry then dead-letter for a
 * failing delivery, and immediate dead-lettering of malformed messages.
 */
@SpringBootTest(properties = {"fincore.security.jwt-secret=integration-test-secret-integration-test-secret",
        "fincore.notification.failure-simulation-enabled=true"})
@Testcontainers
class NotificationKafkaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired JdbcTemplate jdbc;

    private KafkaTemplate<String, String> producer() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "key.serializer", StringSerializer.class, "value.serializer", StringSerializer.class)));
    }

    private Events.TransferOutcome outcome(UUID ref, String description) {
        return new Events.TransferOutcome(ref, "COMPLETED", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1000.00"), "INR", description, null, Instant.now());
    }

    private void awaitCount(String sql, int expected, Object... args) throws InterruptedException {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            Integer n = jdbc.queryForObject(sql, Integer.class, args);
            if (n != null && n == expected) return;
            Thread.sleep(250);
        }
        assertEquals(expected, jdbc.queryForObject(sql, Integer.class, args));
    }

    private List<ConsumerRecord<String, String>> readDlt(String key, int atLeast) {
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(), ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(Topics.dlt(Topics.TRANSFER_COMPLETED)));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end && found.size() < atLeast) {
                c.poll(Duration.ofMillis(500)).forEach(r -> { if (key.equals(r.key())) found.add(r); });
            }
            return found;
        }
    }

    @Test
    void duplicateDeliveryOfTheSameEventSendsEachNotificationOnce() throws Exception {
        UUID ref = UUID.randomUUID();
        EventEnvelope env = EventCodec.envelope(Topics.TRANSFER_COMPLETED, ref.toString(), "corr-dup-0001", outcome(ref, "lunch"));
        String json = EventCodec.toJson(env);
        KafkaTemplate<String, String> p = producer();
        p.send(Topics.TRANSFER_COMPLETED, ref.toString(), json).get();
        p.send(Topics.TRANSFER_COMPLETED, ref.toString(), json).get(); // the broker/relay delivers it twice
        awaitCount("select count(*) from notification_records where transfer_ref = ?", 2, ref); // sender + receiver
        Thread.sleep(2000);
        assertEquals(2, jdbc.queryForObject("select count(*) from notification_records where transfer_ref = ?", Integer.class, ref),
                "duplicate event must not create extra notifications");
        assertEquals(1, jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, env.eventId()));
    }

    @Test
    void persistentDeliveryFailureIsRetriedThenDeadLettered() throws Exception {
        UUID ref = UUID.randomUUID();
        EventEnvelope env = EventCodec.envelope(Topics.TRANSFER_COMPLETED, ref.toString(), "corr-fail-0001",
                outcome(ref, "[simulate-notification-failure] test"));
        producer().send(Topics.TRANSFER_COMPLETED, ref.toString(), EventCodec.toJson(env)).get();
        List<ConsumerRecord<String, String>> dlt = readDlt(ref.toString(), 1);
        assertEquals(1, dlt.size(), "after the retry budget the record lands on transfer.completed.DLT");
        assertNotNull(dlt.get(0).headers().lastHeader("kafka_dlt-exception-fqcn"), "DLT record explains why it failed");
        assertEquals(0, jdbc.queryForObject("select count(*) from notification_records where transfer_ref = ?", Integer.class, ref),
                "failed attempts must not leave partial records");
        assertEquals(0, jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, env.eventId()),
                "the dedupe marker rolled back with the failed attempt");
    }

    @Test
    void malformedMessageGoesStraightToTheDeadLetterTopic() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        producer().send(Topics.TRANSFER_COMPLETED, key, "{ this is not valid json").get();
        assertEquals(1, readDlt(key, 1).size());
    }
}
