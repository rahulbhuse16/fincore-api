package com.fincore.transaction.application;

import com.fincore.platform.contracts.Events;
import com.fincore.transaction.persistence.TransferEntity;

import java.time.Instant;

final class TransferEvents {
    private TransferEvents() {}

    static Events.TransferOutcome outcome(TransferEntity t) {
        return new Events.TransferOutcome(t.getId(), t.getStatus().name(), t.getCustomerId(), t.getDestinationCustomerId(),
                t.getSourceAccountId(), t.getDestinationAccountId(), t.getAmount(), t.getCurrency(), t.getDescription(),
                t.getFailureReason(), Instant.now());
    }

    static Events.TransferInitiated initiated(TransferEntity t) {
        return new Events.TransferInitiated(t.getId(), t.getSourceAccountId(), t.getDestinationAccountId(), t.getAmount(), t.getCurrency());
    }
}
