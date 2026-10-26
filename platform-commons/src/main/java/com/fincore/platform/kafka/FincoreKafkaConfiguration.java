package com.fincore.platform.kafka;

import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventProcessor;
import com.fincore.platform.events.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Topic declaration and consumer error policy.
 *
 * Retry policy: up to 3 redeliveries with exponential backoff (500ms, 1s, 2s). After that, or immediately for
 * InvalidEventException, the record is published to "<topic>.DLT" (same partition) and the offset is committed so
 * one poison message cannot block the partition forever.
 * Ack mode is RECORD (spring.kafka.listener.ack-mode): an offset is committed only after the listener - and
 * therefore its database transaction - has returned successfully.
 */
@Configuration
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class FincoreKafkaConfiguration {

    @Bean
    KafkaAdmin.NewTopics fincoreTopics(@Value("${fincore.kafka.partitions:3}") int partitions,
                                       @Value("${fincore.kafka.replicas:1}") short replicas) {
        NewTopic[] topics = Topics.ALL.stream()
                .flatMap(t -> java.util.stream.Stream.of(t, Topics.dlt(t)))
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(replicas).build())
                .toArray(NewTopic[]::new);
        return new KafkaAdmin.NewTopics(topics);
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.dlt(record.topic()), record.partition()));
        ConsumerRecordRecoverer countingRecoverer = (ConsumerRecord<?, ?> record, Exception ex) -> {
            meters.counter("fincore.kafka.dlt.published", "topic", record.topic()).increment();
            dlt.accept(record, ex);
        };
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(5_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(countingRecoverer, backOff);
        handler.addNotRetryableExceptions(InvalidEventException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                meters.counter("fincore.kafka.consumer.retries", "topic", record.topic()).increment();
            }
        });
        return handler;
    }

    @Bean
    EventProcessor eventProcessor(JdbcTemplate jdbc, MeterRegistry meters) {
        return new EventProcessor(jdbc, meters);
    }
}
