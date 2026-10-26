package com.fincore.reconciliation.api;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import com.fincore.platform.events.EventProcessor;
import com.fincore.platform.events.InvalidEventException;
import com.fincore.reconciliation.application.ReconciliationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationListener {
    static final String CONSUMER = "reconciliation-service";

    private final EventProcessor processor;
    private final ReconciliationService service;

    public ReconciliationListener(EventProcessor processor, ReconciliationService service) {
        this.processor = processor;
        this.service = service;
    }

    @KafkaListener(id = "reconciliation-events", groupId = CONSUMER,
            topics = {Topics.TRANSFER_COMPLETED, Topics.TRANSFER_REVERSED, Topics.SETTLEMENT_RECEIVED})
    public void onMessage(String raw) {
        processor.process(CONSUMER, raw, this::dispatch);
    }

    private void dispatch(EventEnvelope e) {
        switch (e.eventType()) {
            case Topics.TRANSFER_COMPLETED, Topics.TRANSFER_REVERSED ->
                    service.onTransferOutcome(e, EventCodec.payload(e, Events.TransferOutcome.class));
            case Topics.SETTLEMENT_RECEIVED -> service.ingestEvent(EventCodec.payload(e, Events.SettlementReceived.class));
            default -> throw new InvalidEventException("Unexpected event type " + e.eventType());
        }
    }
}
