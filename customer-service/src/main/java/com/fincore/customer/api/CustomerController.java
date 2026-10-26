package com.fincore.customer.api;

import com.fincore.customer.application.CustomerService;
import com.fincore.customer.domain.KycStatus;
import com.fincore.customer.persistence.CustomerEntity;
import com.fincore.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class CustomerController {

    public record CreateCustomerRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "phone must be 7-15 digits, optional leading +") String phone,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Pattern(regexp = "^(PASSPORT|NATIONAL_ID|DRIVING_LICENCE)$", message = "documentType must be PASSPORT, NATIONAL_ID or DRIVING_LICENCE") String documentType,
            @NotBlank @Size(min = 6, max = 30) String documentNumber) {}

    public record UpdateKycRequest(@NotNull KycStatus status, @Size(max = 80) String reason) {}

    public record CustomerResponse(UUID customerId, String fullName, String email, String phone, LocalDate dateOfBirth,
                                   String documentType, String documentLast4, KycStatus kycStatus, String kycReason,
                                   String status, Instant createdAt) {
        static CustomerResponse from(CustomerEntity c) {
            return new CustomerResponse(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getDateOfBirth(),
                    c.getDocumentType(), "****" + c.getDocumentLast4(), c.getKycStatus(), c.getKycReason(), c.getStatus(), c.getCreatedAt());
        }
    }

    /** Minimal internal view for other services. Not routed through the gateway. */
    public record KycView(UUID customerId, KycStatus kycStatus, String status) {}

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @Operation(summary = "Create your own customer profile (runs the SIMULATED KYC check)")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/api/v1/customers")
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest r, @AuthenticationPrincipal Jwt jwt) {
        CustomerEntity c = service.create(CurrentUser.of(jwt), new CustomerService.CreateCommand(r.fullName(), r.email(),
                r.phone(), r.dateOfBirth(), r.documentType(), r.documentNumber()));
        return ResponseEntity.created(URI.create("/api/v1/customers/" + c.getId())).body(CustomerResponse.from(c));
    }

    @Operation(summary = "Get a customer profile (the customer themself or ADMIN)")
    @GetMapping("/api/v1/customers/{customerId}")
    public CustomerResponse get(@PathVariable UUID customerId, @AuthenticationPrincipal Jwt jwt) {
        return CustomerResponse.from(service.get(customerId, CurrentUser.of(jwt)));
    }

    @Operation(summary = "ADMIN: move a customer's KYC status (allowed transitions are enforced)")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/api/v1/customers/{customerId}/kyc")
    public CustomerResponse updateKyc(@PathVariable UUID customerId, @Valid @RequestBody UpdateKycRequest r,
                                      @AuthenticationPrincipal Jwt jwt) {
        return CustomerResponse.from(service.updateKyc(customerId, r.status(), r.reason(), jwt.getSubject()));
    }

    @Operation(summary = "INTERNAL (SERVICE role): KYC status lookup used by account-service")
    @GetMapping("/internal/v1/customers/{customerId}/kyc-status")
    public KycView kyc(@PathVariable UUID customerId) {
        CustomerEntity c = service.get(customerId, new CurrentUser("internal", null, java.util.Set.of("SERVICE")));
        return new KycView(c.getId(), c.getKycStatus(), c.getStatus());
    }
}
