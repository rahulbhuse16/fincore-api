package com.fincore.transaction.application;

import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.platform.web.ApiException;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Administrative operations. Authorization (ADMIN) is enforced at the controller; every action is audited. */
@Service
public class TransferAdminService {
    private final TransferRepository transfers;
    private final TransferWriter writer;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    public TransferAdminService(TransferRepository transfers, TransferWriter writer, OutboxPublisher outbox, AuditLogger audit) {
        this.transfers = transfers;
        this.writer = writer;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Idempotent: repeating the call on a REVERSING/REVERSED transfer changes nothing. */
    @Transactional
    public TransferEntity requestReversal(UUID id, String reason, String actor) {
        TransferEntity t = transfers.lockById(id).orElseThrow(() -> ApiException.notFound("transfer-not-found", "Transfer not found."));
        if (t.getStatus() == TransferStatus.REVERSING || t.getStatus() == TransferStatus.REVERSED) {
            return t;
        }
        if (t.getStatus() != TransferStatus.COMPLETED) {
            throw ApiException.conflict("transfer-not-reversible", "Only COMPLETED transfers can be reversed (status is " + t.getStatus() + ").");
        }
        t.setReversalReason(reason);
        t.setReversalFailureReason(null);
        writer.transition(t, TransferStatus.REVERSING, "reversal requested: " + reason);
        outbox.publish(Topics.TRANSFER_REVERSAL_REQUESTED, id.toString(), new Events.ReversalRequested(id, reason));
        audit.record(actor, "REVERSAL_REQUESTED", "TRANSFER", id.toString(), "SUCCESS", reason);
        return t;
    }

    public enum Decision { APPROVE, REJECT }

    @Transactional
    public TransferEntity review(UUID id, Decision decision, String note, String actor) {
        TransferEntity t = transfers.lockById(id).orElseThrow(() -> ApiException.notFound("transfer-not-found", "Transfer not found."));
        if (t.getStatus() != TransferStatus.PENDING_REVIEW) {
            throw ApiException.conflict("transfer-not-in-review", "Transfer is not awaiting review (status is " + t.getStatus() + ").");
        }
        if (decision == Decision.APPROVE) {
            writer.transition(t, TransferStatus.INITIATED, "approved by reviewer");
            outbox.publish(Topics.TRANSFER_INITIATED, id.toString(), TransferEvents.initiated(t));
        } else {
            t.fail("REJECTED_BY_REVIEWER");
            writer.transition(t, TransferStatus.FAILED, "rejected by reviewer");
            outbox.publish(Topics.TRANSFER_FAILED, id.toString(), TransferEvents.outcome(t));
        }
        audit.record(actor, "TRANSFER_REVIEW_" + decision, "TRANSFER", id.toString(), "SUCCESS", note);
        return t;
    }
}
