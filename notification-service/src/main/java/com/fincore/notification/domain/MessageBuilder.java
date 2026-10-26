package com.fincore.notification.domain;

import java.math.BigDecimal;
import java.util.List;

/** Pure message templating. Amounts are formatted from BigDecimal; no floating point is involved. */
public final class MessageBuilder {
    public enum Role { SENDER, RECEIVER }

    public record Message(String template, String subject, String body) {}

    private MessageBuilder() {}

    /** Who is told about which transfer outcome. A failed transfer is only the sender's business. */
    public static List<Role> recipientsFor(String eventType) {
        return switch (eventType) {
            case "transfer.completed", "transfer.reversed" -> List.of(Role.SENDER, Role.RECEIVER);
            case "transfer.failed" -> List.of(Role.SENDER);
            default -> throw new IllegalArgumentException("No notification template for " + eventType);
        };
    }

    public static Message build(String eventType, Role role, BigDecimal amount, String currency, String description, String failureReason) {
        String money = currency + " " + amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        String note = description == null || description.isBlank() ? "" : " (\"" + description.trim() + "\")";
        return switch (eventType) {
            case "transfer.completed" -> role == Role.SENDER
                    ? new Message("TRANSFER_SENT", "Transfer completed", "You sent " + money + note + ".")
                    : new Message("TRANSFER_RECEIVED", "Money received", "You received " + money + note + ".");
            case "transfer.failed" -> new Message("TRANSFER_FAILED", "Transfer failed",
                    "Your transfer of " + money + " could not be completed" + (failureReason == null ? "" : " (" + failureReason + ")") + ". No money was moved.");
            case "transfer.reversed" -> role == Role.SENDER
                    ? new Message("TRANSFER_REVERSED_SENDER", "Transfer reversed", "A transfer of " + money + " was reversed and the amount returned to your account.")
                    : new Message("TRANSFER_REVERSED_RECEIVER", "Transfer reversed", "A transfer of " + money + " you received was reversed.");
            default -> throw new IllegalArgumentException("No notification template for " + eventType);
        };
    }
}
