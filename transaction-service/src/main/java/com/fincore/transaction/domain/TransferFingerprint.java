package com.fincore.transaction.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Idempotency helpers.
 *  - reference(): a DETERMINISTIC transfer id derived from (customer, idempotency key). Every retry of the same
 *    request maps to the same id, so downstream components (risk, ledger) can deduplicate on it.
 *  - of(): a hash of the request body, used to detect the same key being reused with a DIFFERENT payload.
 */
public final class TransferFingerprint {
    private TransferFingerprint() {}

    public static UUID reference(UUID customerId, String idempotencyKey) {
        return UUID.nameUUIDFromBytes(("fincore-transfer:" + customerId + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    public static String of(UUID source, UUID destination, BigDecimal amount, String currency, String description) {
        String canonical = source + "|" + destination + "|" + amount.stripTrailingZeros().toPlainString() + "|"
                + currency + "|" + (description == null ? "" : description.trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
