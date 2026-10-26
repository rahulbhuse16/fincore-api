package com.fincore.transaction.api;

import com.fincore.transaction.application.TransferAdminService;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {
    private TransferDtos() {}

    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @NotNull @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
            @Digits(integer = 15, fraction = 2, message = "amount supports at most 2 decimal places") BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO-4217 code, e.g. INR") String currency,
            @Size(max = 140) String description) {}

    public record ReverseRequest(@NotBlank @Size(max = 200) String reason) {}

    public record ReviewRequest(@NotNull TransferAdminService.Decision decision, @Size(max = 200) String note) {}

    public record TransferResponse(UUID transferId, TransferStatus status, UUID sourceAccountId, UUID destinationAccountId,
                                   BigDecimal amount, String currency, String description, String failureReason,
                                   String riskDecision, String reversalFailureReason, Instant createdAt, Instant updatedAt,
                                   Instant completedAt, Instant reversedAt) {
        public static TransferResponse from(TransferEntity t) {
            return new TransferResponse(t.getId(), t.getStatus(), t.getSourceAccountId(), t.getDestinationAccountId(),
                    t.getAmount(), t.getCurrency(), t.getDescription(), t.getFailureReason(), t.getRiskDecision(),
                    t.getReversalFailureReason(), t.getCreatedAt(), t.getUpdatedAt(), t.getCompletedAt(), t.getReversedAt());
        }
    }
}
