package com.fincore.transaction.api;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.EventCodec;
import com.fincore.platform.events.EventEnvelope;
import com.fincore.platform.events.EventProcessor;
import com.fincore.platform.events.InvalidEventException;
import com.fincore.transaction.application.TransferSaga;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferEventListeners {
    static final String CONSUMER = "transaction-service";

    private final EventProcessor processor;
    private final TransferSaga saga;

    public TransferEventListeners(EventProcessor processor, TransferSaga saga) {
        this.processor = processor;
        this.saga = saga;
    }

    @KafkaListener(id = "transaction-saga", groupId = CONSUMER, topics = {
            Topics.LEDGER_FUNDS_RESERVED, Topics.LEDGER_FUNDS_REJECTED, Topics.LEDGER_POSTING_COMPLETED,
            Topics.LEDGER_POSTING_FAILED, Topics.LEDGER_REVERSAL_COMPLETED, Topics.LEDGER_REVERSAL_FAILED})
    public void onMessage(String raw) {
        processor.process(CONSUMER, raw, this::dispatch);
    }

    private void dispatch(EventEnvelope e) {
        switch (e.eventType()) {
            case Topics.LEDGER_FUNDS_RESERVED -> saga.onFundsReserved(EventCodec.payload(e, Events.FundsReserved.class).transferRef());
            case Topics.LEDGER_FUNDS_REJECTED -> {
                var p = EventCodec.payload(e, Events.FundsRejected.class);
                saga.onFundsRejected(p.transferRef(), p.reasonCode());
            }
            case Topics.LEDGER_POSTING_COMPLETED -> saga.onPostingCompleted(EventCodec.payload(e, Events.PostingCompleted.class).transferRef());
            case Topics.LEDGER_POSTING_FAILED -> {
                var p = EventCodec.payload(e, Events.PostingFailed.class);
                saga.onPostingFailed(p.transferRef(), p.reasonCode());
            }
            case Topics.LEDGER_REVERSAL_COMPLETED -> saga.onReversalCompleted(EventCodec.payload(e, Events.ReversalCompleted.class).transferRef());
            case Topics.LEDGER_REVERSAL_FAILED -> {
                var p = EventCodec.payload(e, Events.ReversalFailed.class);
                saga.onReversalFailed(p.transferRef(), p.reasonCode());
            }
            default -> throw new InvalidEventException("Unexpected event type " + e.eventType());
        }
    }
}
