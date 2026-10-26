package com.fincore.transaction.application;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reacts to ledger outcomes and advances the transfer state machine. Choreographed saga with the ledger:
 *
 *   transfer.initiated -> [ledger reserves] -> ledger.funds.reserved -> (PENDING) ledger.posting.requested
 *     -> [ledger posts] -> ledger.posting.completed -> (COMPLETED) transfer.completed
 *   any ledger rejection -> FAILED + transfer.failed. The ledger releases its own hold (local compensation).
 *
 * Handlers are state-guarded: an event that does not match the current state (duplicate, late, re-driven) is
 * ignored instead of corrupting the transfer. Each runs inside the consumer's transaction (EventProcessor).
 */
@Service
public class TransferSaga {
    private static final Logger log = LoggerFactory.getLogger(TransferSaga.class);

    private final TransferRepository transfers;
    private final TransferWriter writer;
    private final OutboxPublisher outbox;
    private final MeterRegistry meters;

    public TransferSaga(TransferRepository transfers, TransferWriter writer, OutboxPublisher outbox, MeterRegistry meters) {
        this.transfers = transfers;
        this.writer = writer;
        this.outbox = outbox;
        this.meters = meters;
    }

    @Transactional
    public void onFundsReserved(UUID ref) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.INITIATED, "funds.reserved")) return;
        writer.transition(t, TransferStatus.PENDING, "funds reserved");
        outbox.publish(Topics.LEDGER_POSTING_REQUESTED, ref.toString(), new Events.PostingRequested(ref));
    }

    @Transactional
    public void onFundsRejected(UUID ref, String reason) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.INITIATED, "funds.rejected")) return;
        failTransfer(t, reason);
    }

    @Transactional
    public void onPostingCompleted(UUID ref) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.PENDING, "posting.completed")) return;
        writer.transition(t, TransferStatus.COMPLETED, "journal posted");
        meters.counter("fincore.transfers.completed").increment();
        outbox.publish(Topics.TRANSFER_COMPLETED, ref.toString(), TransferEvents.outcome(t));
    }

    @Transactional
    public void onPostingFailed(UUID ref, String reason) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.PENDING, "posting.failed")) return;
        failTransfer(t, reason);
    }

    @Transactional
    public void onReversalCompleted(UUID ref) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.REVERSING, "reversal.completed")) return;
        writer.transition(t, TransferStatus.REVERSED, "reversal journal posted");
        meters.counter("fincore.transfers.reversed").increment();
        outbox.publish(Topics.TRANSFER_REVERSED, ref.toString(), TransferEvents.outcome(t));
    }

    @Transactional
    public void onReversalFailed(UUID ref, String reason) {
        TransferEntity t = load(ref);
        if (t == null || !expect(t, TransferStatus.REVERSING, "reversal.failed")) return;
        t.setReversalFailureReason(reason);
        writer.transition(t, TransferStatus.COMPLETED, "reversal failed: " + reason);
        meters.counter("fincore.transfers.reversal.failed", "reason", reason).increment();
    }

    private void failTransfer(TransferEntity t, String reason) {
        t.fail(reason);
        writer.transition(t, TransferStatus.FAILED, reason);
        meters.counter("fincore.transfers.failed", "reason", reason).increment();
        outbox.publish(Topics.TRANSFER_FAILED, t.getId().toString(), TransferEvents.outcome(t));
    }

    private TransferEntity load(UUID ref) {
        return transfers.lockById(ref).orElseGet(() -> {
            log.warn("Ignoring event for unknown transfer {}", ref);
            return null;
        });
    }

    private boolean expect(TransferEntity t, TransferStatus expected, String event) {
        if (t.getStatus() != expected) {
            log.info("Ignoring {} for transfer {}: status is {}, expected {}", event, t.getId(), t.getStatus(), expected);
            return false;
        }
        return true;
    }
}
