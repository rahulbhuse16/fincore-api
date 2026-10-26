package com.fincore.reconciliation.api;

import com.fincore.platform.web.PageResponse;
import com.fincore.platform.web.Pagination;
import com.fincore.reconciliation.application.ReconciliationService;
import com.fincore.reconciliation.domain.ExceptionType;
import com.fincore.reconciliation.persistence.ExceptionEntity;
import com.fincore.reconciliation.persistence.Repositories;
import com.fincore.reconciliation.persistence.RunEntity;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Everything here is ADMIN-only. */
@RestController
@RequestMapping("/api/v1/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationController {

    public record SettlementLineDto(@NotNull Integer lineNo, @NotNull @Size(max = 40) String settlementRef, @NotNull UUID transferRef,
                                    @NotNull @DecimalMin("0.01") BigDecimal amount,
                                    @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                    @NotNull @Pattern(regexp = "^(SETTLED|REVERSED)$") String status) {}

    public record IngestRequest(@NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,40}$") String batchId, @NotNull LocalDate settlementDate,
                                @NotEmpty @Size(max = 5000) List<@Valid SettlementLineDto> lines) {}

    public record IngestResponse(String batchId, int received, int newlyStored) {}

    public record RunRequest(@NotNull LocalDate businessDate, @NotNull @Pattern(regexp = "^[A-Za-z0-9_-]{1,40}$") String batchId) {}

    public record RunResponse(UUID runId, LocalDate businessDate, String batchId, int expectedCount, int settlementCount,
                              int matchedCount, int exceptionCount, Instant createdAt) {
        static RunResponse from(RunEntity r) {
            return new RunResponse(r.getId(), r.getBusinessDate(), r.getBatchId(), r.getExpectedCount(), r.getSettlementCount(),
                    r.getMatchedCount(), r.getExceptionCount(), r.getCreatedAt());
        }
    }

    public record ExceptionResponse(UUID id, UUID runId, ExceptionType type, UUID transferId, String details, Instant createdAt) {
        static ExceptionResponse from(ExceptionEntity e) {
            return new ExceptionResponse(e.getId(), e.getRunId(), e.getType(), e.getTransferRef(), e.getDetails(), e.getCreatedAt());
        }
    }

    private final ReconciliationService service;
    private final Repositories.ExceptionRepository exceptions;

    public ReconciliationController(ReconciliationService service, Repositories.ExceptionRepository exceptions) {
        this.service = service;
        this.exceptions = exceptions;
    }

    @Operation(summary = "Upload a synthetic settlement batch. Idempotent per (batchId, lineNo).")
    @PostMapping("/settlement-batches")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest r) {
        int stored = service.ingest(r.batchId(), r.settlementDate(), r.lines().stream().map(l -> new ReconciliationService.IncomingLine(
                l.lineNo(), l.settlementRef(), l.transferRef(), l.amount(), l.currency(), l.status())).toList());
        return new IngestResponse(r.batchId(), r.lines().size(), stored);
    }

    @Operation(summary = "Run reconciliation for (businessDate, batchId). 201 when created; 200 with the stored result when it already exists.")
    @PostMapping("/runs")
    public ResponseEntity<RunResponse> run(@Valid @RequestBody RunRequest r, @AuthenticationPrincipal Jwt jwt) {
        var outcome = service.run(r.businessDate(), r.batchId(), jwt.getSubject());
        return ResponseEntity.status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(RunResponse.from(outcome.run()));
    }

    @GetMapping("/runs/{runId}")
    public RunResponse getRun(@PathVariable UUID runId) {
        return RunResponse.from(service.getRun(runId));
    }

    @Operation(summary = "Exception report, optionally filtered by runId and type; paginated")
    @GetMapping("/exceptions")
    public PageResponse<ExceptionResponse> exceptions(@RequestParam(required = false) UUID runId,
                                                      @RequestParam(required = false) ExceptionType type,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        var pageable = Pagination.of(page, size, null, Set.of("createdAt"), "createdAt");
        return PageResponse.from(exceptions.search(runId, type, pageable), ExceptionResponse::from);
    }
}
