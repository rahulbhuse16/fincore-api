package com.fincore.risk.application;

import com.fincore.platform.audit.AuditLogger;
import com.fincore.risk.domain.Decision;
import com.fincore.risk.domain.RiskEngine;
import com.fincore.risk.domain.RiskHistory;
import com.fincore.risk.domain.RiskRequest;
import com.fincore.risk.domain.RiskResult;
import com.fincore.risk.domain.RiskRules;
import com.fincore.risk.persistence.RiskAssessmentEntity;
import com.fincore.risk.persistence.RiskAssessmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotent on referenceId: a retry (e.g. after a timeout at the caller) returns the stored decision instead of
 * counting the same transfer twice in the velocity and cumulative rules.
 * A per-source-account advisory lock serialises concurrent assessments so two simultaneous requests cannot both
 * slip under a velocity threshold.
 */
@Service
public class RiskAssessmentService {
    public record Outcome(RiskAssessmentEntity assessment, boolean replay) {}

    private final RiskAssessmentRepository repository;
    private final RiskProperties properties;
    private final EntityManager em;
    private final AuditLogger audit;
    private final MeterRegistry meters;
    private final Clock clock;

    public RiskAssessmentService(RiskAssessmentRepository repository, RiskProperties properties, EntityManager em,
                                 AuditLogger audit, MeterRegistry meters, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.em = em;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional
    public Outcome assess(RiskRequest request) {
        em.createNativeQuery("select pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", request.sourceAccountId().toString()).getSingleResult();

        var existing = repository.findByReferenceId(request.referenceId());
        if (existing.isPresent()) {
            return new Outcome(existing.get(), true);
        }
        RiskRules rules = properties.toRules();
        Instant now = clock.instant();
        RiskHistory history = new RiskHistory(
                repository.countSince(request.sourceAccountId(), now.minus(rules.velocityWindow())),
                repository.sumSince(request.sourceAccountId(), now.minus(Duration.ofHours(24))),
                repository.countIdenticalSince(request.sourceAccountId(), request.destinationAccountId(), request.amount(),
                        now.minus(rules.repeatWindow())));

        RiskResult result = RiskEngine.evaluate(request, history, rules);
        RiskAssessmentEntity saved = repository.save(new RiskAssessmentEntity(request.referenceId(), request.customerId(),
                request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currency(),
                result.decision(), result.score(), String.join(",", result.reasons()), RiskRules.VERSION));
        meters.counter("fincore.risk.decisions", "decision", result.decision().name()).increment();
        if (result.decision() != Decision.APPROVE) {
            audit.record("risk-service", "RISK_" + result.decision(), "TRANSFER", request.referenceId().toString(),
                    "SUCCESS", "rules=" + String.join(",", result.reasons()));
        }
        return new Outcome(saved, false);
    }

    @Transactional(readOnly = true)
    public RiskAssessmentEntity get(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                com.fincore.platform.web.ApiException.notFound("assessment-not-found", "Risk assessment not found."));
    }
}
