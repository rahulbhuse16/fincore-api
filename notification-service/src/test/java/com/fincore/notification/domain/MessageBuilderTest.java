package com.fincore.notification.domain;

import com.fincore.notification.domain.MessageBuilder.Role;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageBuilderTest {
    private static final BigDecimal RS1000 = new BigDecimal("1000.0000").setScale(2);

    @Test
    void completedTransferNotifiesBothPartiesWithDifferentWording() {
        assertEquals(List.of(Role.SENDER, Role.RECEIVER), MessageBuilder.recipientsFor("transfer.completed"));
        var sent = MessageBuilder.build("transfer.completed", Role.SENDER, RS1000, "INR", "rent", null);
        var got = MessageBuilder.build("transfer.completed", Role.RECEIVER, RS1000, "INR", "rent", null);
        assertEquals("TRANSFER_SENT", sent.template());
        assertTrue(sent.body().contains("INR 1000.00"));
        assertEquals("TRANSFER_RECEIVED", got.template());
        assertTrue(got.body().startsWith("You received"));
    }

    @Test
    void failedTransferOnlyNotifiesTheSenderAndSaysNoMoneyMoved() {
        assertEquals(List.of(Role.SENDER), MessageBuilder.recipientsFor("transfer.failed"));
        var m = MessageBuilder.build("transfer.failed", Role.SENDER, RS1000, "INR", null, "INSUFFICIENT_FUNDS");
        assertTrue(m.body().contains("INSUFFICIENT_FUNDS"));
        assertTrue(m.body().contains("No money was moved"));
    }

    @Test
    void reversalNotifiesBothParties() {
        assertEquals(2, MessageBuilder.recipientsFor("transfer.reversed").size());
        assertEquals("TRANSFER_REVERSED_RECEIVER", MessageBuilder.build("transfer.reversed", Role.RECEIVER, RS1000, "INR", null, null).template());
    }

    @Test
    void unknownEventTypeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MessageBuilder.recipientsFor("transfer.unknown"));
    }
}
