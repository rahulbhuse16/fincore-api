package com.fincore.risk.api;

import com.fincore.risk.application.RiskAssessmentService;
import com.fincore.risk.domain.Decision;
import com.fincore.risk.domain.RiskRequest;
import com.fincore.risk.persistence.RiskAssessmentEntity;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Risk endpoints are for ADMIN (investigation) and SERVICE (transaction-service) principals only. */
@RestController
@RequestMapping("/api/v1/risk/assessments")
@PreAuthorize("hasAnyRole('ADMIN','SERVICE')")
public class RiskController {

    public record AssessmentRequest(@NotNull UUID referenceId, @NotNull UUID customerId, @NotNull UUID sourceAccountId,
                                    @NotNull UUID destinationAccountId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                    @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

    public record AssessmentResponse(UUID assessmentId, UUID referenceId, Decision decision, int score,
                                     List<String> reasons, String rulesVersion, Instant createdAt) {
        static AssessmentResponse from(RiskAssessmentEntity e) {
            List<String> reasons = e.getReasons().isBlank() ? List.of() : Arrays.asList(e.getReasons().split(","));
            return new AssessmentResponse(e.getId(), e.getReferenceId(), e.getDecision(), e.getScore(), reasons,
                    e.getRulesVersion(), e.getCreatedAt());
        }
    }

    private final RiskAssessmentService service;

    public RiskController(RiskAssessmentService service) {
        this.service = service;
    }

    @Operation(summary = "Assess a transfer. Idempotent on referenceId: 201 when created, 200 when a stored decision is returned.")
    @PostMapping
    public ResponseEntity<AssessmentResponse> assess(@Valid @RequestBody AssessmentRequest r) {
        var outcome = service.assess(new RiskRequest(r.referenceId(), r.customerId(), r.sourceAccountId(),
                r.destinationAccountId(), r.amount(), r.currency()));
        return ResponseEntity.status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(AssessmentResponse.from(outcome.assessment()));
    }

    @Operation(summary = "Fetch a stored assessment (audit)")
    @GetMapping("/{assessmentId}")
    public AssessmentResponse get(@PathVariable UUID assessmentId) {
        return AssessmentResponse.from(service.get(assessmentId));
    }
}
