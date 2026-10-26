package com.fincore.transaction.api;

import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import com.fincore.platform.web.PageResponse;
import com.fincore.platform.web.Pagination;
import com.fincore.transaction.application.TransferAdminService;
import com.fincore.transaction.application.TransferCommandService;
import com.fincore.transaction.application.TransferQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class TransferController {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final TransferCommandService commands;
    private final TransferQueryService queries;
    private final TransferAdminService admin;

    public TransferController(TransferCommandService commands, TransferQueryService queries, TransferAdminService admin) {
        this.commands = commands;
        this.queries = queries;
        this.admin = admin;
    }

    @Operation(summary = "Submit an internal transfer (CUSTOMER). Asynchronous: returns 202 with status INITIATED; poll GET /transfers/{id}. "
            + "A replay of the same Idempotency-Key and body returns 200 with header Idempotent-Replay: true.")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/transfers")
    public ResponseEntity<TransferDtos.TransferResponse> create(
            @Parameter(description = "8-64 chars [A-Za-z0-9_-]; required for all financial writes")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferDtos.CreateTransferRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("invalid-idempotency-key", "Header Idempotency-Key is required (8-64 chars of A-Z a-z 0-9 _ -).");
        }
        var result = commands.initiate(CurrentUser.of(jwt), new TransferCommandService.CreateCommand(idempotencyKey,
                body.sourceAccountId(), body.destinationAccountId(), body.amount(), body.currency(), body.description()));
        var response = TransferDtos.TransferResponse.from(result.transfer());
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/transfers/" + response.transferId())).body(response);
    }

    @Operation(summary = "Get a transfer (initiator, receiving customer, or ADMIN)")
    @GetMapping("/transfers/{transferId}")
    public TransferDtos.TransferResponse get(@PathVariable UUID transferId, @AuthenticationPrincipal Jwt jwt) {
        return TransferDtos.TransferResponse.from(queries.get(transferId, CurrentUser.of(jwt)));
    }

    @Operation(summary = "Paginated transaction history of an account (owner or ADMIN). sort=createdAt|amount|status,asc|desc")
    @GetMapping("/accounts/{accountId}/transactions")
    public PageResponse<TransferDtos.TransferResponse> history(@PathVariable UUID accountId,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size,
                                                               @RequestParam(required = false) String sort,
                                                               @AuthenticationPrincipal Jwt jwt) {
        var pageable = Pagination.of(page, size, sort, Set.of("createdAt", "amount", "status"), "createdAt");
        return PageResponse.from(queries.forAccount(accountId, CurrentUser.of(jwt), pageable), TransferDtos.TransferResponse::from);
    }

    @Operation(summary = "ADMIN: reverse a COMPLETED transfer with a compensating ledger journal. Asynchronous and idempotent.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/transfers/{transferId}/reverse")
    public ResponseEntity<TransferDtos.TransferResponse> reverse(@PathVariable UUID transferId,
                                                                 @Valid @RequestBody TransferDtos.ReverseRequest body,
                                                                 @AuthenticationPrincipal Jwt jwt) {
        var t = admin.requestReversal(transferId, body.reason(), jwt.getSubject());
        return ResponseEntity.accepted().body(TransferDtos.TransferResponse.from(t));
    }

    @Operation(summary = "ADMIN: approve or reject a transfer held in PENDING_REVIEW by the risk engine")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/transfers/{transferId}/review")
    public TransferDtos.TransferResponse review(@PathVariable UUID transferId,
                                                @Valid @RequestBody TransferDtos.ReviewRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return TransferDtos.TransferResponse.from(admin.review(transferId, body.decision(), body.note(), jwt.getSubject()));
    }
}
