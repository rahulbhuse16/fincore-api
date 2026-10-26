package com.fincore.transaction.application;

import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferHistoryEntity;
import com.fincore.transaction.persistence.TransferHistoryRepository;
import com.fincore.transaction.persistence.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundary for creating a transfer: row + history + outbox event commit atomically. */
@Service
public class TransferWriter {
    private final TransferRepository transfers;
    private final TransferHistoryRepository history;
    private final OutboxPublisher outbox;

    public TransferWriter(TransferRepository transfers, TransferHistoryRepository history, OutboxPublisher outbox) {
        this.transfers = transfers;
        this.history = history;
        this.outbox = outbox;
    }

    /** Throws DataIntegrityViolationException if the (customer, idempotency key) already exists. */
    @Transactional
    public TransferEntity persistNew(TransferEntity transfer) {
        TransferEntity saved = transfers.saveAndFlush(transfer);
        history.save(new TransferHistoryEntity(saved.getId(), null, saved.getStatus().name(),
                saved.getFailureReason() != null ? saved.getFailureReason() : "created"));
        String key = saved.getId().toString();
        switch (saved.getStatus()) {
            case INITIATED -> outbox.publish(Topics.TRANSFER_INITIATED, key, TransferEvents.initiated(saved));
            case FAILED -> outbox.publish(Topics.TRANSFER_FAILED, key, TransferEvents.outcome(saved));
            default -> { /* PENDING_REVIEW: nothing moves until an admin decides */ }
        }
        return saved;
    }

    /** Shared by saga, admin and recovery code: apply a state change and record it. Caller supplies the transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void transition(TransferEntity t, TransferStatus next, String reason) {
        TransferStatus previous = t.transitionTo(next);
        history.save(new TransferHistoryEntity(t.getId(), previous.name(), next.name(), reason));
    }
}
