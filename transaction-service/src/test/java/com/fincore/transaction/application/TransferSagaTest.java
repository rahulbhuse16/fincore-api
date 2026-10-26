package com.fincore.transaction.application;

import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferEntity;
import com.fincore.transaction.persistence.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure Mockito unit test of the saga's state guards (no Spring context, no Docker). */
class TransferSagaTest {
    private TransferRepository transfers;
    private TransferWriter writer;
    private OutboxPublisher outbox;
    private TransferSaga saga;
    private UUID ref;

    @BeforeEach
    void setUp() {
        transfers = mock(TransferRepository.class);
        writer = mock(TransferWriter.class);
        outbox = mock(OutboxPublisher.class);
        saga = new TransferSaga(transfers, writer, outbox, new SimpleMeterRegistry());
        ref = UUID.randomUUID();
    }

    private TransferEntity transferIn(TransferStatus status) {
        TransferEntity t = new TransferEntity(ref, UUID.randomUUID(), "key-12345678", "fp", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("100.00"), "INR", "x", status, "APPROVE", UUID.randomUUID(), null);
        when(transfers.lockById(ref)).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void fundsReservedMovesInitiatedToPendingAndRequestsPosting() {
        TransferEntity t = transferIn(TransferStatus.INITIATED);
        saga.onFundsReserved(ref);
        verify(writer).transition(eq(t), eq(TransferStatus.PENDING), any());
        verify(outbox).publish(eq(Topics.LEDGER_POSTING_REQUESTED), eq(ref.toString()), any(Events.PostingRequested.class));
    }

    @Test
    void duplicateFundsReservedForAnAlreadyPendingTransferIsIgnored() {
        transferIn(TransferStatus.PENDING);
        saga.onFundsReserved(ref);
        verifyNoInteractions(writer, outbox);
    }

    @Test
    void postingCompletedCompletesAndPublishesTheOutcome() {
        TransferEntity t = transferIn(TransferStatus.PENDING);
        saga.onPostingCompleted(ref);
        verify(writer).transition(eq(t), eq(TransferStatus.COMPLETED), any());
        verify(outbox).publish(eq(Topics.TRANSFER_COMPLETED), eq(ref.toString()), any(Events.TransferOutcome.class));
    }

    @Test
    void lateEventsForTerminalTransfersChangeNothing() {
        transferIn(TransferStatus.FAILED);
        saga.onPostingCompleted(ref);
        saga.onFundsReserved(ref);
        verifyNoInteractions(writer, outbox);
    }

    @Test
    void fundsRejectedFailsTheTransferWithTheLedgersReason() {
        TransferEntity t = transferIn(TransferStatus.INITIATED);
        saga.onFundsRejected(ref, "INSUFFICIENT_FUNDS");
        verify(writer).transition(eq(t), eq(TransferStatus.FAILED), eq("INSUFFICIENT_FUNDS"));
        verify(outbox).publish(eq(Topics.TRANSFER_FAILED), eq(ref.toString()), any(Events.TransferOutcome.class));
        org.junit.jupiter.api.Assertions.assertEquals("INSUFFICIENT_FUNDS", t.getFailureReason());
    }

    @Test
    void reversalFailureReturnsTheTransferToCompletedWithoutPublishingReversed() {
        TransferEntity t = transferIn(TransferStatus.REVERSING);
        saga.onReversalFailed(ref, "INSUFFICIENT_FUNDS_FOR_REVERSAL");
        verify(writer).transition(eq(t), eq(TransferStatus.COMPLETED), any());
        verify(outbox, never()).publish(eq(Topics.TRANSFER_REVERSED), ArgumentMatchers.anyString(), any());
        org.junit.jupiter.api.Assertions.assertEquals("INSUFFICIENT_FUNDS_FOR_REVERSAL", t.getReversalFailureReason());
    }

    @Test
    void eventsForUnknownTransfersAreIgnored() {
        when(transfers.lockById(ref)).thenReturn(Optional.empty());
        saga.onPostingCompleted(ref);
        verifyNoInteractions(writer, outbox);
    }
}
