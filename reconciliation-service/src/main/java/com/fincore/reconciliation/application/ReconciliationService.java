package com.fincore.reconciliation.application;

import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.events.EventEnvelope;
import com.fincore.platform.web.ApiException;
import com.fincore.reconciliation.domain.ExpectedTransaction;
import com.fincore.reconciliation.domain.ReconResult;
import com.fincore.reconciliation.domain.Reconciler;
import com.fincore.reconciliation.domain.SettlementLine;
import com.fincore.reconciliation.persistence.ExceptionEntity;
import com.fincore.reconciliation.persistence.ExpectedTransactionEntity;
import com.fincore.reconciliation.persistence.Repositories;
import com.fincore.reconciliation.persistence.RunEntity;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationService {
    public record RunOutcome(RunEntity run, boolean replay) {}
    public record IncomingLine(int lineNo, String settlementRef, UUID transferRef, java.math.BigDecimal amount,
                               String currency, String status) {}

    private final Repositories.ExpectedRepository expected;
    private final Repositories.SettlementRepository settlements;
    private final Repositories.RunRepository runs;
    private final Repositories.ExceptionRepository exceptions;
    private final JdbcTemplate jdbc;
    private final AuditLogger audit;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public ReconciliationService(Repositories.ExpectedRepository expected, Repositories.SettlementRepository settlements,
                                 Repositories.RunRepository runs, Repositories.ExceptionRepository exceptions,
                                 JdbcTemplate jdbc, AuditLogger audit, MeterRegistry meters, PlatformTransactionManager tm) {
        this.expected = expected;
        this.settlements = settlements;
        this.runs = runs;
        this.exceptions = exceptions;
        this.jdbc = jdbc;
        this.audit = audit;
        this.meters = meters;
        this.tx = new TransactionTemplate(tm);
    }

    // ---------------------------------------------------------------- expected side (from Kafka)

    /** transfer.completed creates the expectation; transfer.reversed flips it to REVERSED. Safe to replay. */
    @Transactional
    public void onTransferOutcome(EventEnvelope event, Events.TransferOutcome o) {
        var existing = expected.findById(o.transferRef());
        if ("transfer.reversed".equals(event.eventType())) {
            existing.ifPresentOrElse(ExpectedTransactionEntity::markReversed, () -> expected.save(new ExpectedTransactionEntity(
                    o.transferRef(), event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate(), o.amount(), o.currency(), "REVERSED")));
        } else if (existing.isEmpty()) {
            expected.save(new ExpectedTransactionEntity(o.transferRef(), event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    o.amount(), o.currency(), "COMPLETED"));
        } // a late/duplicate completed event never downgrades a REVERSED expectation
    }

    // ---------------------------------------------------------------- settlement side (REST upload or Kafka)

    /** Idempotent per (batchId, lineNo). Returns how many NEW lines were stored. */
    @Transactional
    public int ingest(String batchId, LocalDate settlementDate, List<IncomingLine> lines) {
        int inserted = 0;
        for (IncomingLine l : lines) {
            inserted += jdbc.update("""
                    INSERT INTO settlement_records(id, batch_id, line_no, settlement_ref, transfer_ref, amount, currency, status, settlement_date)
                    VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (batch_id, line_no) DO NOTHING""",
                    UUID.randomUUID(), batchId, l.lineNo(), l.settlementRef(), l.transferRef(), l.amount(), l.currency(),
                    l.status(), settlementDate);
        }
        return inserted;
    }

    @Transactional
    public void ingestEvent(Events.SettlementReceived e) {
        ingest(e.batchId(), e.settlementDate(), List.of(new IncomingLine(e.lineNo(), e.settlementRef(), e.transferRef(),
                e.amount(), e.currency(), e.status())));
    }

    // ---------------------------------------------------------------- the run

    /** Idempotent on (businessDate, batchId): asking again returns the stored run; no duplicate exceptions are created. */
    public RunOutcome run(LocalDate businessDate, String batchId, String actor) {
        var existing = runs.findByBusinessDateAndBatchId(businessDate, batchId);
        if (existing.isPresent()) {
            return new RunOutcome(existing.get(), true);
        }
        try {
            RunEntity run = tx.execute(status -> execute(businessDate, batchId, actor));
            return new RunOutcome(run, false);
        } catch (DataIntegrityViolationException race) {
            return new RunOutcome(runs.findByBusinessDateAndBatchId(businessDate, batchId).orElseThrow(() -> race), true);
        }
    }

    private RunEntity execute(LocalDate businessDate, String batchId, String actor) {
        var lines = settlements.findByBatchId(batchId);
        if (lines.isEmpty()) {
            throw ApiException.unprocessable("batch-not-found", "No settlement lines have been ingested for batch " + batchId + ".");
        }
        var expectedRows = expected.findByBusinessDate(businessDate);
        ReconResult result = Reconciler.reconcile(
                expectedRows.stream().map(e -> new ExpectedTransaction(e.getTransferRef(), e.getAmount(), e.getCurrency(), e.getStatus())).toList(),
                new java.util.ArrayList<>(lines.stream().map(s -> new SettlementLine(s.getBatchId(), s.getLineNo(),
                        s.getSettlementRef(), s.getTransferRef(), s.getAmount(), s.getCurrency(), s.getStatus())).toList()));

        RunEntity run = runs.saveAndFlush(new RunEntity(businessDate, batchId, expectedRows.size(), lines.size(),
                result.matched(), result.exceptions().size(), actor));
        result.exceptions().forEach(x -> {
            exceptions.save(new ExceptionEntity(run.getId(), x.type(), x.transferRef(), x.details()));
            meters.counter("fincore.reconciliation.exceptions", "type", x.type().name()).increment();
        });
        meters.counter("fincore.reconciliation.runs").increment();
        audit.record(actor, "RECONCILIATION_RUN", "RECONCILIATION", run.getId().toString(), "SUCCESS",
                "date=" + businessDate + " batch=" + batchId + " exceptions=" + result.exceptions().size());
        return run;
    }

    @Transactional(readOnly = true)
    public RunEntity getRun(UUID id) {
        return runs.findById(id).orElseThrow(() -> ApiException.notFound("run-not-found", "Reconciliation run not found."));
    }
}
