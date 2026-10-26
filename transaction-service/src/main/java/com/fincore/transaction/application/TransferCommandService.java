package com.fincore.transaction.application;

import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import com.fincore.transaction.client.AccountClient;
import com.fincore.transaction.client.RiskClient;
import com.fincore.transaction.domain.AccountView;
import com.fincore.transaction.domain.TransferFingerprint;
import com.fincore.transaction.domain.TransferPolicy;
import com.fincore.transaction.domain.TransferRuleViolation;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates transfer creation. Deliberately NOT @Transactional: the remote calls (accounts, risk) must not hold a
 * database transaction open. Only TransferWriter.persistNew opens one, right at the end.
 *
 * Idempotency, step by step:
 *  1. The transfer id is derived deterministically from (customer, Idempotency-Key).
 *  2. An existing row for that key is a replay: same payload -> return the stored result (HTTP 200);
 *     different payload -> 422 idempotency-key-reuse.
 *  3. Two identical requests racing each other both reach the insert; UNIQUE (customer_id, idempotency_key)
 *     lets exactly one succeed. The loser catches the violation, reloads the winner's row and replays it.
 *  4. The risk call is idempotent on the same reference, so retries after a timeout cannot double count.
 */
@Service
public class TransferCommandService {
    private static final Logger log = LoggerFactory.getLogger(TransferCommandService.class);
    static final Set<TransferStatus> COUNTED_TOWARDS_DAILY_LIMIT =
            EnumSet.of(TransferStatus.PENDING_REVIEW, TransferStatus.INITIATED, TransferStatus.PENDING,
                    TransferStatus.COMPLETED, TransferStatus.REVERSING);

    public record CreateCommand(String idempotencyKey, UUID sourceAccountId, UUID destinationAccountId,
                                BigDecimal amount, String currency, String description) {}

    public record InitiationResult(TransferEntity transfer, boolean replay) {}

    private final TransferRepository transfers;
    private final TransferWriter writer;
    private final AccountClient accounts;
    private final RiskClient risk;
    private final MeterRegistry meters;

    public TransferCommandService(TransferRepository transfers, TransferWriter writer, AccountClient accounts,
                                  RiskClient risk, MeterRegistry meters) {
        this.transfers = transfers;
        this.writer = writer;
        this.accounts = accounts;
        this.risk = risk;
        this.meters = meters;
    }

    public InitiationResult initiate(CurrentUser user, CreateCommand cmd) {
        UUID customerId = user.customerId();
        if (customerId == null) {
            throw ApiException.forbidden("customer-profile-required", "Your user is not linked to a customer profile.");
        }
        String fingerprint = TransferFingerprint.of(cmd.sourceAccountId(), cmd.destinationAccountId(), cmd.amount(),
                cmd.currency(), cmd.description());

        Optional<TransferEntity> existing = transfers.findByCustomerIdAndIdempotencyKey(customerId, cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }

        // Ownership: a foreign or unknown source account is reported as 404 so account ids cannot be probed.
        AccountView source = accounts.find(cmd.sourceAccountId())
                .filter(a -> customerId.equals(a.customerId()))
                .orElseThrow(() -> ApiException.notFound("source-account-not-found", "Source account not found."));
        AccountView destination = accounts.find(cmd.destinationAccountId())
                .orElseThrow(() -> ApiException.unprocessable("destination-account-not-found", "Destination account not found."));

        BigDecimal usedToday = transfers.sumAmountSince(cmd.sourceAccountId(), Instant.now().minus(Duration.ofHours(24)),
                COUNTED_TOWARDS_DAILY_LIMIT);
        try {
            TransferPolicy.validate(cmd.sourceAccountId(), cmd.destinationAccountId(), cmd.amount(), cmd.currency(),
                    source, destination, usedToday);
        } catch (TransferRuleViolation v) {
            throw ApiException.unprocessable(v.code().toLowerCase().replace('_', '-'), v.getMessage());
        }

        UUID reference = TransferFingerprint.reference(customerId, cmd.idempotencyKey());
        RiskClient.RiskResponse decision = risk.assess(new RiskClient.RiskRequest(reference, customerId,
                cmd.sourceAccountId(), cmd.destinationAccountId(), cmd.amount(), cmd.currency()));

        TransferStatus initial = switch (decision.decision()) {
            case "APPROVE" -> TransferStatus.INITIATED;
            case "REVIEW" -> TransferStatus.PENDING_REVIEW;
            default -> TransferStatus.FAILED; // REJECT, or anything unexpected: fail closed
        };
        TransferEntity entity = new TransferEntity(reference, customerId, cmd.idempotencyKey(), fingerprint,
                cmd.sourceAccountId(), cmd.destinationAccountId(), destination.customerId(), cmd.amount(), cmd.currency(),
                cmd.description(), initial, decision.decision(), decision.assessmentId(),
                initial == TransferStatus.FAILED ? "RISK_REJECTED" : null);
        try {
            TransferEntity saved = writer.persistNew(entity);
            meters.counter("fincore.transfers.created", "status", saved.getStatus().name()).increment();
            if (saved.getStatus() == TransferStatus.FAILED) {
                meters.counter("fincore.transfers.failed", "reason", "RISK_REJECTED").increment();
            }
            return new InitiationResult(saved, false);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request with the same key won the insert. Replay ITS result instead of failing.
            TransferEntity winner = transfers.findByCustomerIdAndIdempotencyKey(customerId, cmd.idempotencyKey())
                    .orElseThrow(() -> race);
            log.info("Concurrent duplicate request resolved by unique constraint for reference {}", reference);
            return replayOrConflict(winner, fingerprint);
        }
    }

    private InitiationResult replayOrConflict(TransferEntity existing, String fingerprint) {
        if (existing.getRequestFingerprint().equals(fingerprint)) {
            meters.counter("fincore.idempotency.replays").increment();
            return new InitiationResult(existing, true);
        }
        meters.counter("fincore.idempotency.conflicts").increment();
        throw ApiException.unprocessable("idempotency-key-reuse",
                "This Idempotency-Key was already used with a different request body.");
    }
}
