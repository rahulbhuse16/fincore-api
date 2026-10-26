package com.fincore.customer.domain;

import java.time.LocalDate;
import java.time.Period;

/**
 * SIMULATED identity verification. No real document check happens. Deterministic rules so tests and demos are
 * repeatable:  age < 18 -> REJECTED;  document number starting FAIL -> REJECTED;  starting MANUAL -> PENDING
 * (needs an admin); anything else -> VERIFIED.
 */
public final class KycSimulator {
    public record Result(KycStatus status, String reason) {}

    private KycSimulator() {}

    public static Result evaluate(LocalDate dateOfBirth, String documentNumber, LocalDate today) {
        if (Period.between(dateOfBirth, today).getYears() < 18) {
            return new Result(KycStatus.REJECTED, "UNDERAGE");
        }
        String doc = documentNumber == null ? "" : documentNumber.trim().toUpperCase();
        if (doc.length() < 6) {
            return new Result(KycStatus.REJECTED, "DOCUMENT_INVALID");
        }
        if (doc.startsWith("FAIL")) {
            return new Result(KycStatus.REJECTED, "DOCUMENT_CHECK_FAILED");
        }
        if (doc.startsWith("MANUAL")) {
            return new Result(KycStatus.PENDING, "MANUAL_REVIEW_REQUIRED");
        }
        return new Result(KycStatus.VERIFIED, "AUTO_VERIFIED");
    }
}
