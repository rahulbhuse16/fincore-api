package com.fincore.notification.application;

import com.fincore.notification.domain.MessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * SIMULATED email/push channel: it only logs. The log line contains the template name and an opaque recipient id,
 * never the message body (which contains amounts) and never personal data.
 *
 * Test hook (off by default, enabled in the demo profile): when fincore.notification.failure-simulation-enabled=true,
 * a transfer whose description contains "[simulate-notification-failure]" makes delivery fail every time. This is
 * how the retry-then-DLT behaviour is demonstrated reproducibly (scripts/demo-dlt.sh).
 */
@Component
public class NotificationSender {
    public static final String FAILURE_MARKER = "[simulate-notification-failure]";
    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    private final boolean failureSimulation;

    public NotificationSender(@Value("${fincore.notification.failure-simulation-enabled:false}") boolean failureSimulation) {
        this.failureSimulation = failureSimulation;
    }

    public void send(UUID recipientCustomerId, MessageBuilder.Message message, String transferDescription) {
        if (failureSimulation && transferDescription != null && transferDescription.contains(FAILURE_MARKER)) {
            throw new NotificationDeliveryException("Simulated channel failure for template " + message.template());
        }
        log.info("Simulated EMAIL sent: template={} recipient={}", message.template(), recipientCustomerId);
    }
}
