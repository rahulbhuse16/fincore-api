package com.fincore.transaction.application;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovery for transfers that sit in an in-flight state for too long (ledger down, DLT'd message, lost event).
 * It RE-DRIVES the pending command (same transfer reference, new event id). It never fails the transfer on a timeout:
 * the ledger commands are idempotent on the reference, so a re-drive either completes the work or re-emits the
 * stored outcome. After max attempts the transfer is flagged needs_attention for a human, not guessed at.
 */
@Component
public class StuckTransferRecovery {
    private static final Logger log = LoggerFactory.getLogger(StuckTransferRecovery.class);

    private final TransferRepository transfers;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Duration staleAfter;
    private final int maxAttempts;

    public StuckTransferRecovery(TransferRepository transfers, OutboxPublisher outbox, PlatformTransactionManager tm,
                                 MeterRegistry meters,
                                 @Value("${fincore.recovery.stale-after-seconds:120}") long staleAfterSeconds,
                                 @Value("${fincore.recovery.max-attempts:10}") int maxAttempts) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(tm);
        this.meters = meters;
        this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
        this.maxAttempts = maxAttempts;
        Gauge.builder("fincore.transfers.needs_attention", transfers, TransferRepository::countByNeedsAttentionTrue).register(meters);
    }

    @Scheduled(fixedDelayString = "${fincore.recovery.poll-interval-ms:30000}")
    public void redriveStuckTransfers() {
        Integer n = tx.execute(status -> {
            List<TransferEntity> stuck = transfers.lockStuck(Instant.now().minus(staleAfter), 20);
            stuck.forEach(this::redrive);
            return stuck.size();
        });
        if (n != null && n > 0) {
            log.info("Re-drove {} stuck transfer(s)", n);
        }
    }

    private void redrive(TransferEntity t) {
        String key = t.getId().toString();
        switch (t.getStatus()) {
            case INITIATED -> outbox.publish(Topics.TRANSFER_INITIATED, key, TransferEvents.initiated(t));
            case PENDING -> outbox.publish(Topics.LEDGER_POSTING_REQUESTED, key, new Events.PostingRequested(t.getId()));
            case REVERSING -> outbox.publish(Topics.TRANSFER_REVERSAL_REQUESTED, key,
                    new Events.ReversalRequested(t.getId(), t.getReversalReason()));
            default -> { return; }
        }
        t.recordRecoveryAttempt(maxAttempts);
        meters.counter("fincore.transfers.redriven", "status", t.getStatus().name()).increment();
    }
}
