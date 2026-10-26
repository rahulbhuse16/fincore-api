package com.fincore.account.api;

import com.fincore.account.application.AccountService;
import com.fincore.account.domain.AccountStatus;
import com.fincore.account.persistence.AccountEntity;
import com.fincore.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {

    public record OpenAccountRequest(UUID customerId, @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be INR or USD") String currency) {}
    public record StatusRequest(@NotNull AccountStatus status) {}
    public record LimitsRequest(@NotNull @DecimalMin("0.01") BigDecimal perTransferLimit, @NotNull @DecimalMin("0.01") BigDecimal dailyLimit) {}

    public record AccountResponse(UUID accountId, UUID customerId, String accountNumber, String currency, AccountStatus status,
                                  BigDecimal perTransferLimit, BigDecimal dailyLimit, Instant createdAt) {
        static AccountResponse from(AccountEntity a) {
            return new AccountResponse(a.getId(), a.getCustomerId(), a.getAccountNumber(), a.getCurrency(), a.getStatus(),
                    a.getPerTransferLimit(), a.getDailyLimit(), a.getCreatedAt());
        }
    }

    /** Internal contract consumed by transaction-service. */
    public record TransferView(UUID accountId, UUID customerId, String currency, String status,
                               BigDecimal perTransferLimit, BigDecimal dailyLimit) {}

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @Operation(summary = "Open an account. Requires a KYC-verified customer. CUSTOMER opens for self; ADMIN may pass customerId.")
    @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')")
    @PostMapping("/api/v1/accounts")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest r, @AuthenticationPrincipal Jwt jwt) {
        AccountEntity a = service.open(CurrentUser.of(jwt), r.customerId(), r.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + a.getId())).body(AccountResponse.from(a));
    }

    @Operation(summary = "Get account details (owner or ADMIN). Balances: GET /api/v1/accounts/{id}/balance (served by the ledger)")
    @GetMapping("/api/v1/accounts/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(service.get(accountId, CurrentUser.of(jwt)));
    }

    @Operation(summary = "List a customer's accounts (the customer or ADMIN)")
    @GetMapping("/api/v1/customers/{customerId}/accounts")
    public List<AccountResponse> list(@PathVariable UUID customerId, @AuthenticationPrincipal Jwt jwt) {
        return service.listForCustomer(customerId, CurrentUser.of(jwt)).stream().map(AccountResponse::from).toList();
    }

    @Operation(summary = "ADMIN: freeze, unfreeze or close an account")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/api/v1/accounts/{accountId}/status")
    public AccountResponse status(@PathVariable UUID accountId, @Valid @RequestBody StatusRequest r, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(service.changeStatus(accountId, r.status(), jwt.getSubject()));
    }

    @Operation(summary = "ADMIN: change transfer limits")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/api/v1/accounts/{accountId}/limits")
    public AccountResponse limits(@PathVariable UUID accountId, @Valid @RequestBody LimitsRequest r, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(service.changeLimits(accountId, r.perTransferLimit(), r.dailyLimit(), jwt.getSubject()));
    }

    @Operation(summary = "INTERNAL (SERVICE role): account facts for transfer validation. Not routed through the gateway.")
    @GetMapping("/internal/v1/accounts/{accountId}/transfer-view")
    public TransferView transferView(@PathVariable UUID accountId) {
        AccountEntity a = service.internalView(accountId);
        return new TransferView(a.getId(), a.getCustomerId(), a.getCurrency(), a.getStatus().name(),
                a.getPerTransferLimit(), a.getDailyLimit());
    }
}
