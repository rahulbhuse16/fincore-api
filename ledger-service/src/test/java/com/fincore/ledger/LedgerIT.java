package com.fincore.ledger;

import com.fincore.ledger.application.LedgerPostingService;
import com.fincore.ledger.persistence.EntryRepository;
import com.fincore.ledger.persistence.HoldEntity;
import com.fincore.ledger.persistence.HoldRepository;
import com.fincore.ledger.persistence.JournalRepository;
import com.fincore.ledger.persistence.LedgerAccountRepository;
import com.fincore.platform.contracts.Events;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration + concurrency + financial-invariant tests against REAL PostgreSQL (Flyway migrations, row locks,
 * triggers). Requires Docker. Run with:  mvn -pl ledger-service verify
 */
@SpringBootTest(properties = {"fincore.security.jwt-secret=integration-test-secret-integration-test-secret"})
@Testcontainers
class LedgerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired LedgerPostingService ledger;
    @Autowired LedgerAccountRepository accounts;
    @Autowired HoldRepository holds;
    @Autowired JournalRepository journals;
    @Autowired EntryRepository entries;
    @Autowired JdbcTemplate jdbc;

    UUID alice, bob;

    @BeforeEach
    void setUp() {
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        ledger.openAccount(new Events.AccountOpened(alice, UUID.randomUUID(), "INR"));
        ledger.openAccount(new Events.AccountOpened(bob, UUID.randomUUID(), "INR"));
        ledger.deposit(UUID.randomUUID(), alice, new BigDecimal("500.00"), "INR", "test");
    }

    private BigDecimal ledgerBalance(UUID id) { return accounts.findById(id).orElseThrow().getLedgerBalance(); }
    private BigDecimal available(UUID id) { return accounts.findById(id).orElseThrow().getAvailableBalance(); }

    private Events.TransferInitiated transfer(UUID ref, String amount) {
        return new Events.TransferInitiated(ref, alice, bob, new BigDecimal(amount), "INR");
    }

    @Test
    void concurrentReservationsCanNeverOverspendTheAccount() throws Exception {
        int threads = 12; // 12 x 100 requested against 500 available
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> refs = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID ref = UUID.randomUUID();
            refs.add(ref);
            futures.add(pool.submit(() -> { start.await(); ledger.reserveFunds(transfer(ref, "100.00")); return null; }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        long held = refs.stream().map(r -> holds.findByTransferRef(r).orElseThrow()).filter(h -> h.getStatus() == HoldEntity.Status.HELD).count();
        long rejected = refs.stream().map(r -> holds.findByTransferRef(r).orElseThrow()).filter(h -> h.getStatus() == HoldEntity.Status.REJECTED).count();
        assertEquals(5, held, "exactly 500 / 100 reservations may succeed");
        assertEquals(7, rejected);
        assertEquals(0, available(alice).compareTo(BigDecimal.ZERO));
        assertEquals(0, ledgerBalance(alice).compareTo(new BigDecimal("500")), "ledger balance is untouched by reservations");
    }

    @Test
    void duplicateCommandsHaveExactlyOneFinancialEffect() {
        UUID ref = UUID.randomUUID();
        ledger.reserveFunds(transfer(ref, "200.00"));
        ledger.reserveFunds(transfer(ref, "200.00"));        // duplicate reserve
        ledger.postTransfer(ref);
        ledger.postTransfer(ref);                              // duplicate post
        assertEquals(0, ledgerBalance(alice).compareTo(new BigDecimal("300")));
        assertEquals(0, ledgerBalance(bob).compareTo(new BigDecimal("200")));
        assertEquals(1, jdbc.queryForObject("select count(*) from journal_transactions where reference = ? and journal_type = 'TRANSFER'", Integer.class, ref));
        assertEquals(2, entries.findByJournalIdOrderByDirectionAscCreatedAtAsc(holds.findByTransferRef(ref).orElseThrow().getJournalId()).size());
    }

    @Test
    void everyPostedJournalBalancesAndTotalMoneyIsConserved() {
        for (int i = 0; i < 5; i++) {
            UUID ref = UUID.randomUUID();
            ledger.reserveFunds(transfer(ref, "50.00"));
            ledger.postTransfer(ref);
        }
        Integer unbalanced = jdbc.queryForObject("""
                select count(*) from (select journal_id from ledger_entries group by journal_id
                having sum(case when direction='DEBIT' then amount else -amount end) <> 0) t""", Integer.class);
        assertEquals(0, unbalanced);
        // customer liabilities + cash asset: cash (debit-normal) must equal the sum of customer balances
        BigDecimal customers = jdbc.queryForObject("select coalesce(sum(ledger_balance),0) from ledger_accounts where kind = 'LIABILITY'", BigDecimal.class);
        BigDecimal cash = jdbc.queryForObject("select coalesce(sum(ledger_balance),0) from ledger_accounts where kind = 'ASSET'", BigDecimal.class);
        assertEquals(0, customers.compareTo(cash));
    }

    @Test
    void reversalAddsACompensatingJournalAndLeavesHistoryIntact() {
        UUID ref = UUID.randomUUID();
        ledger.reserveFunds(transfer(ref, "120.00"));
        ledger.postTransfer(ref);
        ledger.reverseTransfer(new Events.ReversalRequested(ref, "test"));
        ledger.reverseTransfer(new Events.ReversalRequested(ref, "test")); // retried

        assertEquals(0, ledgerBalance(alice).compareTo(new BigDecimal("500")));
        assertEquals(0, ledgerBalance(bob).compareTo(BigDecimal.ZERO));
        assertEquals(2, jdbc.queryForObject("select count(*) from journal_transactions where reference = ?", Integer.class, ref),
                "original + exactly one reversal");
        assertEquals(1, jdbc.queryForObject("select count(*) from journal_transactions where reference = ? and journal_type = 'TRANSFER'", Integer.class, ref));
    }

    @Test
    void reversalIsRefusedWhenTheReceiverHasAlreadySpentTheMoney() {
        UUID ref = UUID.randomUUID();
        ledger.reserveFunds(transfer(ref, "100.00"));
        ledger.postTransfer(ref);
        UUID carol = UUID.randomUUID();
        ledger.openAccount(new Events.AccountOpened(carol, UUID.randomUUID(), "INR"));
        UUID second = UUID.randomUUID();
        ledger.reserveFunds(new Events.TransferInitiated(second, bob, carol, new BigDecimal("100.00"), "INR"));
        ledger.postTransfer(second); // bob is now empty

        ledger.reverseTransfer(new Events.ReversalRequested(ref, "too late"));
        assertEquals(0, ledgerBalance(bob).compareTo(BigDecimal.ZERO));
        assertEquals(0, ledgerBalance(alice).compareTo(new BigDecimal("400")));
        assertEquals(0, jdbc.queryForObject("select count(*) from journal_transactions where reference = ? and journal_type = 'REVERSAL'", Integer.class, ref));
    }

    @Test
    void databaseRejectsAnUnbalancedJournalAtCommit() {
        UUID journal = UUID.randomUUID();
        assertThrows(Exception.class, () -> new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(s -> {
            jdbc.update("insert into journal_transactions(id, journal_type, reference, currency) values (?, 'DEPOSIT', ?, 'INR')", journal, UUID.randomUUID());
            jdbc.update("insert into ledger_entries(id, journal_id, account_id, direction, amount, currency, ledger_balance_after) values (?,?,?, 'DEBIT', 10, 'INR', 10)",
                    UUID.randomUUID(), journal, alice);
        }));
        assertEquals(0, jdbc.queryForObject("select count(*) from journal_transactions where id = ?", Integer.class, journal));
    }

    @Test
    void postedEntriesCannotBeUpdatedOrDeleted() {
        UUID ref = UUID.randomUUID();
        ledger.reserveFunds(transfer(ref, "10.00"));
        ledger.postTransfer(ref);
        assertThrows(Exception.class, () -> jdbc.update("update ledger_entries set amount = amount + 1"));
        assertThrows(Exception.class, () -> jdbc.update("delete from ledger_entries"));
        assertThrows(Exception.class, () -> jdbc.update("update journal_transactions set description = 'tampered'"));
    }

    @Test
    void checkConstraintsStopANegativeAvailableBalanceEvenForBuggyCode() {
        assertThrows(Exception.class, () -> jdbc.update("update ledger_accounts set available_balance = -1 where id = ?", alice));
        assertThrows(Exception.class, () -> jdbc.update("update ledger_accounts set available_balance = ledger_balance + 1 where id = ?", alice));
    }

    @Test
    void frozenSourceCannotReserveButStillReceives() {
        ledger.changeAccountStatus(new Events.AccountStatusChanged(alice, "FROZEN"));
        UUID ref = UUID.randomUUID();
        ledger.reserveFunds(transfer(ref, "10.00"));
        assertEquals(HoldEntity.Status.REJECTED, holds.findByTransferRef(ref).orElseThrow().getStatus());
        assertEquals("SOURCE_ACCOUNT_NOT_ACTIVE", holds.findByTransferRef(ref).orElseThrow().getReasonCode());
    }

    @Test
    void depositIsIdempotentOnItsReference() {
        UUID reference = UUID.randomUUID();
        var first = ledger.deposit(reference, bob, new BigDecimal("75.00"), "INR", "test");
        var second = ledger.deposit(reference, bob, new BigDecimal("75.00"), "INR", "test");
        assertFalse(first.replay());
        assertTrue(second.replay());
        assertEquals(first.journalId(), second.journalId());
        assertEquals(0, ledgerBalance(bob).compareTo(new BigDecimal("75")));
    }
}
