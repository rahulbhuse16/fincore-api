package com.fincore.ledger.api;

import com.fincore.ledger.application.LedgerPostingService;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import com.fincore.platform.events.EventProcessor;
import com.fincore.platform.events.InvalidEventException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka entry points of the ledger. Each message is handled in one DB transaction together with its dedupe marker
 * (EventProcessor); the offset is committed only after that transaction commits (ack-mode RECORD).
 */
@Component
public class LedgerEventListeners {
    static final String CONSUMER = "ledger-service";

    private final EventProcessor processor;
    private final LedgerPostingService ledger;

    public LedgerEventListeners(EventProcessor processor, LedgerPostingService ledger) {
        this.processor = processor;
        this.ledger = ledger;
    }

    @KafkaListener(id = "ledger-commands", groupId = CONSUMER, topics = {
            Topics.ACCOUNT_OPENED, Topics.ACCOUNT_STATUS_CHANGED, Topics.TRANSFER_INITIATED,
            Topics.LEDGER_POSTING_REQUESTED, Topics.TRANSFER_REVERSAL_REQUESTED})
    public void onMessage(String raw) {
        processor.process(CONSUMER, raw, this::dispatch);
    }

    private void dispatch(EventEnvelope e) {
        switch (e.eventType()) {
            case Topics.ACCOUNT_OPENED -> ledger.openAccount(EventCodec.payload(e, Events.AccountOpened.class));
            case Topics.ACCOUNT_STATUS_CHANGED -> ledger.changeAccountStatus(EventCodec.payload(e, Events.AccountStatusChanged.class));
            case Topics.TRANSFER_INITIATED -> ledger.reserveFunds(EventCodec.payload(e, Events.TransferInitiated.class));
            case Topics.LEDGER_POSTING_REQUESTED -> ledger.postTransfer(EventCodec.payload(e, Events.PostingRequested.class).transferRef());
            case Topics.TRANSFER_REVERSAL_REQUESTED -> ledger.reverseTransfer(EventCodec.payload(e, Events.ReversalRequested.class));
            default -> throw new InvalidEventException("Unexpected event type " + e.eventType());
        }
    }
}
