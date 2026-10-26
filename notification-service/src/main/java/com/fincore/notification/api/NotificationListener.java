package com.fincore.notification.api;

import com.fincore.notification.application.NotificationService;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {
    static final String CONSUMER = "notification-service";

    private final EventProcessor processor;
    private final NotificationService service;

    public NotificationListener(EventProcessor processor, NotificationService service) {
        this.processor = processor;
        this.service = service;
    }

    @KafkaListener(id = "notification-events", groupId = CONSUMER,
            topics = {Topics.TRANSFER_COMPLETED, Topics.TRANSFER_FAILED, Topics.TRANSFER_REVERSED})
    public void onMessage(String raw) {
        processor.process(CONSUMER, raw, service::handle);
    }
}
