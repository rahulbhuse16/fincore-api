package com.fincore.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fincore.outbox.enabled", havingValue = "true")
public class FincoreOutboxConfiguration {

    @Bean
    OutboxStore outboxStore(JdbcTemplate jdbc) {
        return new OutboxStore(jdbc);
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxStore store) {
        return new OutboxPublisher(store);
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, PlatformTransactionManager tm,
                            MeterRegistry meters,
                            @Value("${fincore.outbox.batch-size:50}") int batchSize,
                            @Value("${fincore.outbox.send-timeout-ms:5000}") long sendTimeoutMs) {
        return new OutboxRelay(store, kafka, new TransactionTemplate(tm), meters, batchSize, sendTimeoutMs);
    }
}
