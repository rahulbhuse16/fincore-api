package com.fincore.transaction;

import com.fincore.platform.security.CurrentUser;
import com.fincore.platform.web.ApiException;
import com.fincore.transaction.application.TransferCommandService;
import com.fincore.transaction.client.AccountClient;
import com.fincore.transaction.client.RiskClient;
import com.fincore.transaction.domain.AccountView;
import com.fincore.transaction.domain.TransferStatus;
import com.fincore.transaction.persistence.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Requires Docker (PostgreSQL + Kafka via Testcontainers). Downstream HTTP clients are mocked. */
@SpringBootTest(properties = {"fincore.security.jwt-secret=integration-test-secret-integration-test-secret",
        "fincore.outbox.poll-interval-ms=100000"}) // keep the relay quiet so the outbox rows stay inspectable
@Testcontainers
class TransferIdempotencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired TransferCommandService service;
    @Autowired TransferRepository transfers;
    @Autowired JdbcTemplate jdbc;
    @MockBean AccountClient accounts;
    @MockBean RiskClient risk;

    UUID customer, srcId, dstId;
    CurrentUser user;

    @BeforeEach
    void setUp() {
        customer = UUID.randomUUID();
        srcId = UUID.randomUUID();
        dstId = UUID.randomUUID();
        user = new CurrentUser(UUID.randomUUID().toString(), customer, Set.of("CUSTOMER"));
        when(accounts.find(eq(srcId))).thenReturn(Optional.of(new AccountView(srcId, customer, "INR", "ACTIVE", new BigDecimal("50000"), new BigDecimal("200000"))));
        when(accounts.find(eq(dstId))).thenReturn(Optional.of(new AccountView(dstId, UUID.randomUUID(), "INR", "ACTIVE", new BigDecimal("50000"), new BigDecimal("200000"))));
        when(risk.assess(any())).thenAnswer(i -> new RiskClient.RiskResponse(UUID.randomUUID(), "APPROVE", 0, List.of()));
    }

    private TransferCommandService.CreateCommand cmd(String key, String amount) {
        return new TransferCommandService.CreateCommand(key, srcId, dstId, new BigDecimal(amount), "INR", "rent");
    }

    @Test
    void sequentialRetryReturnsTheSameTransferWithoutASecondEffect() {
        var first = service.initiate(user, cmd("key-sequential-1", "1000.00"));
        var retry = service.initiate(user, cmd("key-sequential-1", "1000.00"));
        assertFalse(first.replay());
        assertTrue(retry.replay());
        assertEquals(first.transfer().getId(), retry.transfer().getId());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfer.initiated' and event_key = ?", Integer.class, first.transfer().getId().toString()));
    }

    @Test
    void manyConcurrentIdenticalRequestsCreateExactlyOneTransfer() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferCommandService.InitiationResult>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> { start.await(); return service.initiate(user, cmd("key-concurrent-1", "250.00")); }));
        }
        start.countDown();
        Set<UUID> ids = new java.util.HashSet<>();
        long created = 0;
        for (var f : results) {
            var r = f.get(30, TimeUnit.SECONDS);
            ids.add(r.transfer().getId());
            if (!r.replay()) created++;
        }
        pool.shutdown();
        assertEquals(1, ids.size(), "every caller must see the same transfer");
        assertEquals(1, created, "exactly one request may create it");
        assertEquals(1, transfers.count());
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfer.initiated'", Integer.class));
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
        service.initiate(user, cmd("key-reuse-0001", "100.00"));
        ApiException e = assertThrows(ApiException.class, () -> service.initiate(user, cmd("key-reuse-0001", "100.01")));
        assertEquals("idempotency-key-reuse", e.getCode());
        assertEquals(1, transfers.count());
    }

    @Test
    void riskServiceOutageFailsClosedAndCreatesNothing() {
        when(risk.assess(any())).thenThrow(ApiException.unavailable("risk-unavailable", "down"));
        ApiException e = assertThrows(ApiException.class, () -> service.initiate(user, cmd("key-risk-down-1", "100.00")));
        assertEquals("risk-unavailable", e.getCode());
        assertEquals(0, transfers.count());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events", Integer.class));
    }

    @Test
    void riskRejectionIsRecordedAsFailedAndEmitsAFailureEventButNoLedgerCommand() {
        when(risk.assess(any())).thenReturn(new RiskClient.RiskResponse(UUID.randomUUID(), "REJECT", 100, List.of("AMOUNT_OVER_HARD_LIMIT")));
        var r = service.initiate(user, cmd("key-risk-rej-01", "100.00"));
        assertEquals(TransferStatus.FAILED, r.transfer().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfer.initiated'", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from outbox_events where topic = 'transfer.failed'", Integer.class));
    }

    @Test
    void riskReviewHoldsTheTransferWithoutMovingAnything() {
        when(risk.assess(any())).thenReturn(new RiskClient.RiskResponse(UUID.randomUUID(), "REVIEW", 40, List.of("AMOUNT_OVER_REVIEW_THRESHOLD")));
        var r = service.initiate(user, cmd("key-risk-rev-01", "100.00"));
        assertEquals(TransferStatus.PENDING_REVIEW, r.transfer().getStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox_events", Integer.class));
    }

    @Test
    void spendingFromSomeoneElsesAccountIsReportedAsNotFound() {
        UUID other = UUID.randomUUID();
        CurrentUser mallory = new CurrentUser("mallory", other, Set.of("CUSTOMER"));
        ApiException e = assertThrows(ApiException.class, () -> service.initiate(mallory, cmd("key-theft-0001", "10.00")));
        assertEquals("source-account-not-found", e.getCode());
        assertEquals(0, transfers.count());
    }
}
