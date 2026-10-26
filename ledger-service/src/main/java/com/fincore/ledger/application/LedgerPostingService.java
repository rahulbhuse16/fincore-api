package com.fincore.ledger.application;

import com.fincore.ledger.domain.AccountKind;
import com.fincore.ledger.domain.AccountState;
import com.fincore.ledger.domain.AccountStatus;
import com.fincore.ledger.domain.JournalApplier;
import com.fincore.ledger.domain.JournalDraft;
import com.fincore.ledger.domain.JournalLine;
import com.fincore.ledger.domain.JournalType;
import com.fincore.ledger.domain.Money;
import com.fincore.ledger.domain.PostingResult;
import com.fincore.ledger.domain.PostingRules;
import com.fincore.ledger.domain.SystemAccounts;
import com.fincore.ledger.persistence.EntryEntity;
import com.fincore.ledger.persistence.EntryRepository;
import com.fincore.ledger.persistence.HoldEntity;
import com.fincore.ledger.persistence.HoldRepository;
import com.fincore.ledger.persistence.JournalEntity;
import com.fincore.ledger.persistence.JournalRepository;
import com.fincore.ledger.persistence.LedgerAccountEntity;
import com.fincore.ledger.persistence.LedgerAccountRepository;
import com.fincore.platform.audit.AuditLogger;
import com.fincore.platform.contracts.Events;
import com.fincore.platform.contracts.Topics;
import com.fincore.platform.events.InvalidEventException;
import com.fincore.platform.outbox.OutboxPublisher;
import com.fincore.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The ledger's write side. Every public method is ONE local ACID transaction in the ledger database:
 * balance changes, hold state, journal + entries and the outbox events they trigger commit together or not at all.
 *
 * Funds protection: account rows are locked (SELECT ... FOR UPDATE, ordered by id) before balances are read, so two
 * concurrent spenders are serialised and the second sees the first one's reservation. CHECK constraints in the
 * schema are the backstop.
 *
 * Every command is idempotent on its business key (transfer_ref): a duplicate or re-driven command re-emits the
 * stored outcome and never changes balances twice.
 */
@Service
public class LedgerPostingService {
    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final LedgerAccountRepository accounts;
    private final HoldRepository holds;
    private final JournalRepository journals;
    private final EntryRepository entries;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;
    private final MeterRegistry meters;

    public LedgerPostingService(LedgerAccountRepository accounts, HoldRepository holds, JournalRepository journals,
                                EntryRepository entries, OutboxPublisher outbox, AuditLogger audit, MeterRegistry meters) {
        this.accounts = accounts;
        this.holds = holds;
        this.journals = journals;
        this.entries = entries;
        this.outbox = outbox;
        this.audit = audit;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ account projection

    @Transactional
    public void openAccount(Events.AccountOpened event) {
        if (accounts.existsById(event.accountId())) {
            return; // duplicate delivery
        }
        accounts.save(new LedgerAccountEntity(event.accountId(), event.customerId(), event.currency(),
                AccountKind.LIABILITY, AccountStatus.ACTIVE));
    }

    @Transactional
    public void changeAccountStatus(Events.AccountStatusChanged event) {
        LedgerAccountEntity account = accounts.findById(event.accountId())
                .orElseThrow(() -> new AccountNotYetKnownException(event.accountId()));
        account.changeStatus(AccountStatus.valueOf(event.status()));
    }

    // ------------------------------------------------------------------ phase 1: reserve funds

    @Transactional
    public void reserveFunds(Events.TransferInitiated cmd) {
        Money money = parseMoney(cmd.amount(), cmd.currency());
        Map<UUID, LedgerAccountEntity> locked = lock(cmd.sourceAccountId(), cmd.destinationAccountId());
        // Checked AFTER taking the account locks, so a concurrent duplicate sees the first one's committed hold.
        Optional<HoldEntity> existing = holds.findByTransferRef(cmd.transferRef());
        if (existing.isPresent()) {
            emitReservationOutcome(existing.get());
            return;
        }
        LedgerAccountEntity source = require(locked, cmd.sourceAccountId());
        LedgerAccountEntity destination = require(locked, cmd.destinationAccountId());

        Optional<String> rejection = PostingRules.reserveRejection(source.toState(), destination.toState(), money);
        if (rejection.isPresent()) {
            HoldEntity rejected = holds.save(new HoldEntity(cmd.transferRef(), cmd.sourceAccountId(),
                    cmd.destinationAccountId(), money.amount(), money.currency(), HoldEntity.Status.REJECTED, rejection.get()));
            meters.counter("fincore.ledger.reservations.rejected", "reason", rejection.get()).increment();
            emitReservationOutcome(rejected);
            return;
        }
        source.applyBalances(source.toState().balances().reserve(money.amount()));
        HoldEntity hold = holds.save(new HoldEntity(cmd.transferRef(), cmd.sourceAccountId(), cmd.destinationAccountId(),
                money.amount(), money.currency(), HoldEntity.Status.HELD, null));
        meters.counter("fincore.ledger.reservations.accepted").increment();
        emitReservationOutcome(hold);
    }

    private void emitReservationOutcome(HoldEntity hold) {
        String key = hold.getTransferRef().toString();
        switch (hold.getStatus()) {
            case HELD, POSTED -> outbox.publish(Topics.LEDGER_FUNDS_RESERVED, key, new Events.FundsReserved(hold.getTransferRef()));
            case REJECTED -> outbox.publish(Topics.LEDGER_FUNDS_REJECTED, key, new Events.FundsRejected(hold.getTransferRef(), hold.getReasonCode()));
            case RELEASED -> outbox.publish(Topics.LEDGER_FUNDS_REJECTED, key, new Events.FundsRejected(hold.getTransferRef(), "HOLD_RELEASED"));
        }
    }

    // ------------------------------------------------------------------ phase 2: post the journal

    @Transactional
    public void postTransfer(UUID transferRef) {
        String key = transferRef.toString();
        Optional<HoldEntity> found = holds.lockByTransferRef(transferRef);
        if (found.isEmpty()) {
            outbox.publish(Topics.LEDGER_POSTING_FAILED, key, new Events.PostingFailed(transferRef, "HOLD_NOT_FOUND"));
            return;
        }
        HoldEntity hold = found.get();
        switch (hold.getStatus()) {
            case POSTED -> {
                outbox.publish(Topics.LEDGER_POSTING_COMPLETED, key, new Events.PostingCompleted(transferRef, hold.getJournalId()));
                return;
            }
            case RELEASED, REJECTED -> {
                outbox.publish(Topics.LEDGER_POSTING_FAILED, key, new Events.PostingFailed(transferRef, "HOLD_" + hold.getStatus()));
                return;
            }
            case HELD -> { /* continue below */ }
        }

        Map<UUID, LedgerAccountEntity> locked = lock(hold.getSourceAccountId(), hold.getDestinationAccountId());
        LedgerAccountEntity source = require(locked, hold.getSourceAccountId());
        LedgerAccountEntity destination = require(locked, hold.getDestinationAccountId());

        Optional<String> rejection = PostingRules.postRejection(destination.toState());
        if (rejection.isPresent()) {
            // Compensation inside the ledger: give the reserved funds back, record why, tell the saga.
            source.applyBalances(source.toState().balances().release(hold.getAmount()));
            hold.markReleased(rejection.get());
            meters.counter("fincore.ledger.postings.failed", "reason", rejection.get()).increment();
            outbox.publish(Topics.LEDGER_POSTING_FAILED, key, new Events.PostingFailed(transferRef, rejection.get()));
            return;
        }

        Money money = new Money(hold.getAmount(), hold.getCurrency());
        JournalDraft draft = JournalDraft.transfer(transferRef, source.getId(), destination.getId(), money);
        Map<UUID, AccountState> states = Map.of(source.getId(), source.toState(), destination.getId(), destination.toState());
        PostingResult result = JournalApplier.apply(draft, states, Set.of(source.getId()));
        UUID journalId = persist(draft, result, locked);
        hold.markPosted(journalId);
        meters.counter("fincore.ledger.journals.posted", "type", JournalType.TRANSFER.name()).increment();
        outbox.publish(Topics.LEDGER_POSTING_COMPLETED, key, new Events.PostingCompleted(transferRef, journalId));
    }

    // ------------------------------------------------------------------ reversal (compensating journal)

    @Transactional
    public void reverseTransfer(Events.ReversalRequested cmd) {
        UUID ref = cmd.transferRef();
        String key = ref.toString();
        Optional<HoldEntity> found = holds.lockByTransferRef(ref);
        if (found.isEmpty() || found.get().getStatus() != HoldEntity.Status.POSTED) {
            outbox.publish(Topics.LEDGER_REVERSAL_FAILED, key, new Events.ReversalFailed(ref, "TRANSFER_NOT_POSTED"));
            return;
        }
        HoldEntity hold = found.get();
        Optional<JournalEntity> already = journals.findByReferenceAndJournalType(ref, JournalType.REVERSAL);
        if (already.isPresent()) {
            outbox.publish(Topics.LEDGER_REVERSAL_COMPLETED, key, new Events.ReversalCompleted(ref, already.get().getId()));
            return;
        }
        JournalEntity original = journals.findByReferenceAndJournalType(ref, JournalType.TRANSFER)
                .orElseThrow(() -> new IllegalStateException("posted hold without a journal for " + ref));

        Map<UUID, LedgerAccountEntity> locked = lock(hold.getSourceAccountId(), hold.getDestinationAccountId());
        LedgerAccountEntity source = require(locked, hold.getSourceAccountId());
        LedgerAccountEntity destination = require(locked, hold.getDestinationAccountId());

        Optional<String> rejection = PostingRules.reversalRejection(source.toState(), destination.toState(), hold.getAmount());
        if (rejection.isPresent()) {
            meters.counter("fincore.ledger.reversals.failed", "reason", rejection.get()).increment();
            outbox.publish(Topics.LEDGER_REVERSAL_FAILED, key, new Events.ReversalFailed(ref, rejection.get()));
            return;
        }
        List<JournalLine> originalLines = entries.findByJournalIdOrderByDirectionAscCreatedAtAsc(original.getId()).stream()
                .map(e -> new JournalLine(e.getAccountId(), e.getDirection(), new Money(e.getAmount(), e.getCurrency())))
                .toList();
        JournalDraft draft = JournalDraft.reversalOf(original.getId(), ref, hold.getCurrency(), originalLines);
        Map<UUID, AccountState> states = Map.of(source.getId(), source.toState(), destination.getId(), destination.toState());
        PostingResult result = JournalApplier.apply(draft, states, Set.of());
        UUID reversalJournalId = persist(draft, result, locked);
        audit.record("ledger-service", "TRANSFER_REVERSED", "TRANSFER", key, "SUCCESS", "reason=" + abbreviate(cmd.reason()));
        meters.counter("fincore.ledger.journals.posted", "type", JournalType.REVERSAL.name()).increment();
        outbox.publish(Topics.LEDGER_REVERSAL_COMPLETED, key, new Events.ReversalCompleted(ref, reversalJournalId));
    }

    // ------------------------------------------------------------------ simulated deposits (ADMIN)

    public record DepositOutcome(UUID journalId, boolean replay) {}

    @Transactional
    public DepositOutcome deposit(UUID reference, UUID accountId, BigDecimal amount, String currency, String actor) {
        Money money;
        try {
            money = parseMoney(amount, currency);
        } catch (InvalidEventException e) {
            throw ApiException.badRequest("invalid-amount", e.getMessage());
        }
        UUID cashId = SystemAccounts.cashClearing(currency)
                .orElseThrow(() -> ApiException.unprocessable("unsupported-currency", "No cash-clearing account for " + currency));
        Map<UUID, LedgerAccountEntity> locked = lock(cashId, accountId);
        Optional<JournalEntity> existing = journals.findByReferenceAndJournalType(reference, JournalType.DEPOSIT);
        if (existing.isPresent()) {
            return new DepositOutcome(existing.get().getId(), true);
        }
        LedgerAccountEntity target = locked.get(accountId);
        if (target == null) {
            throw ApiException.notFound("account-not-found", "Ledger account not found");
        }
        if (target.getKind() != AccountKind.LIABILITY || target.getStatus() != AccountStatus.ACTIVE
                || !target.getCurrency().equals(currency)) {
            throw ApiException.unprocessable("account-not-eligible", "Account must be an ACTIVE customer account in " + currency);
        }
        LedgerAccountEntity cash = require(locked, cashId);
        JournalDraft draft = JournalDraft.deposit(reference, cashId, accountId, money);
        PostingResult result = JournalApplier.apply(draft,
                Map.of(cashId, cash.toState(), accountId, target.toState()), Set.of());
        UUID journalId = persist(draft, result, locked);
        audit.record(actor, "SIMULATED_DEPOSIT", "ACCOUNT", accountId.toString(), "SUCCESS", "journal=" + journalId);
        meters.counter("fincore.ledger.journals.posted", "type", JournalType.DEPOSIT.name()).increment();
        return new DepositOutcome(journalId, false);
    }

    // ------------------------------------------------------------------ helpers

    private UUID persist(JournalDraft draft, PostingResult result, Map<UUID, LedgerAccountEntity> locked) {
        JournalEntity journal = journals.saveAndFlush(new JournalEntity(draft.type(), draft.reference(),
                draft.reversesJournalId(), draft.currency(), draft.description()));
        for (PostingResult.AppliedLine applied : result.lines()) {
            JournalLine line = applied.line();
            entries.save(new EntryEntity(journal.getId(), line.accountId(), line.direction(), line.amount().amount(),
                    line.amount().currency(), applied.ledgerBalanceAfter()));
        }
        result.balancesAfter().forEach((id, balances) -> locked.get(id).applyBalances(balances));
        log.info("Posted {} journal {} for reference {}", draft.type(), journal.getId(), draft.reference());
        return journal.getId();
    }

    private Map<UUID, LedgerAccountEntity> lock(UUID a, UUID b) {
        Map<UUID, LedgerAccountEntity> map = new HashMap<>();
        accounts.lockAllByIdIn(new java.util.HashSet<>(java.util.Arrays.asList(a, b))).forEach(acc -> map.put(acc.getId(), acc));
        return map;
    }

    private static LedgerAccountEntity require(Map<UUID, LedgerAccountEntity> locked, UUID id) {
        LedgerAccountEntity account = locked.get(id);
        if (account == null) {
            throw new AccountNotYetKnownException(id);
        }
        return account;
    }

    private static Money parseMoney(BigDecimal amount, String currency) {
        try {
            Money money = new Money(amount, currency);
            if (!money.isPositive()) {
                throw new IllegalArgumentException("amount must be positive");
            }
            return money;
        } catch (IllegalArgumentException | NullPointerException e) {
            // A malformed command can never succeed: non-retryable, goes straight to the DLT.
            throw new InvalidEventException("Invalid monetary amount: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.substring(0, Math.min(80, s.length()));
    }
}
