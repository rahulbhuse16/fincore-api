package com.fincore.ledger.api;

import com.fincore.ledger.application.LedgerPostingService;
import com.fincore.ledger.application.LedgerQueryService;
import com.fincore.ledger.persistence.EntryEntity;
import com.fincore.ledger.persistence.LedgerAccountEntity;
import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.PageResponse;
import com.fincore.platform.web.Pagination;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {
    private final LedgerQueryService queries;
    private final LedgerPostingService posting;

    public LedgerController(LedgerQueryService queries, LedgerPostingService posting) {
        this.queries = queries;
        this.posting = posting;
    }

    @Operation(summary = "Ledger and available balance (owner or ADMIN). Reachable through the gateway as GET /api/v1/accounts/{id}/balance")
    @GetMapping("/accounts/{accountId}/balance")
    public LedgerDtos.BalanceResponse balance(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        LedgerAccountEntity a = queries.accountFor(accountId, CurrentUser.of(jwt));
        BigDecimal held = a.getLedgerBalance().subtract(a.getAvailableBalance());
        return new LedgerDtos.BalanceResponse(a.getId(), a.getCurrency(), a.getLedgerBalance(), a.getAvailableBalance(),
                held, a.getStatus().name(), Instant.now());
    }

    @Operation(summary = "Paginated ledger entries of an account (owner or ADMIN). sort=createdAt,desc|asc")
    @GetMapping("/accounts/{accountId}/entries")
    public PageResponse<LedgerDtos.EntryResponse> entries(@PathVariable UUID accountId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size,
                                                          @RequestParam(required = false) String sort,
                                                          @AuthenticationPrincipal Jwt jwt) {
        var pageable = Pagination.of(page, size, sort, Set.of("createdAt"), "createdAt");
        return PageResponse.from(queries.entries(accountId, CurrentUser.of(jwt), pageable), LedgerController::toResponse);
    }

    @Operation(summary = "A journal with its entries. Customers only see the lines touching their own accounts.")
    @GetMapping("/journals/{journalId}")
    public LedgerDtos.JournalResponse journal(@PathVariable UUID journalId, @AuthenticationPrincipal Jwt jwt) {
        var view = queries.journal(journalId, CurrentUser.of(jwt));
        var j = view.journal();
        return new LedgerDtos.JournalResponse(j.getId(), j.getJournalType(), j.getReference(), j.getReversesJournalId(),
                j.getCurrency(), j.getPostedAt(), view.entries().stream().map(LedgerController::toResponse).toList());
    }

    @Operation(summary = "ADMIN: credit synthetic funds to a customer account (double-entry against the cash-clearing account). Idempotent on 'reference'.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/deposits")
    public ResponseEntity<LedgerDtos.DepositResponse> deposit(@Valid @RequestBody LedgerDtos.DepositRequest request,
                                                              @AuthenticationPrincipal Jwt jwt) {
        var outcome = posting.deposit(request.reference(), request.accountId(), request.amount(), request.currency(), jwt.getSubject());
        return ResponseEntity.status(outcome.replay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(new LedgerDtos.DepositResponse(outcome.journalId(), outcome.replay()));
    }

    private static LedgerDtos.EntryResponse toResponse(EntryEntity e) {
        return new LedgerDtos.EntryResponse(e.getId(), e.getJournalId(), e.getDirection(), e.getAmount(), e.getCurrency(),
                e.getLedgerBalanceAfter(), e.getCreatedAt());
    }
}
