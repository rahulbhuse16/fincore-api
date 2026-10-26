# Interview guide

Each topic: **plain language**, then **the deeper technical answer**, then a likely follow-up.

## 1. "Why a double-entry ledger?"
*Plain:* every payment is recorded as money leaving one place and the same amount arriving in another. If the two sides ever
disagree, you know exactly something is broken, and you can replay history to see why.
*Deep:* a mutable balance column loses provenance and cannot be audited. A journal with balanced lines gives an immutable,
append-only record; balances are a cache of the sum. I enforce balance with a deferred constraint trigger checked at COMMIT and
immutability with triggers, so even a buggy deploy cannot corrupt history. Customer accounts are liabilities (credit-normal), the
cash-clearing account is an asset (debit-normal); the invariant `sum(customers) = cash` is testable.
*Follow-up: "how do you fix a mistake?"* Never edit: post a reversal journal that flips every line and links to the original
(`reverses_journal_id` is UNIQUE, so it can only be reversed once).

## 2. "How do you stop two payments spending the same money?"
*Plain:* the account is locked for the instant we check and reserve the funds, so the second payment sees what the first left.
*Deep:* `SELECT ... FOR UPDATE ORDER BY id` on both accounts, then `available >= amount` check and `available -= amount` in one
transaction; the funds are a *hold*, distinct from ledger balance until posting. Ordered locks avoid deadlocks; CHECK constraints are the
backstop. Tested with 12 threads against 5 slots (`LedgerIT`). *Follow-up: "why not optimistic locking?"* It works but needs
retry loops and wastes work on hot accounts; the pessimistic lock is simple and the critical section is tiny.

## 3. "Explain idempotency."
*Plain:* sending the same request twice has the same effect as sending it once.
*Deep:* required `Idempotency-Key`; transfer id is a deterministic UUID of (customer, key); UNIQUE (customer, key) resolves races; a
SHA-256 fingerprint of the body detects key reuse with different content (422). Every downstream step is idempotent on the same reference
(risk UNIQUE reference, ledger UNIQUE transfer_ref), and every consumer dedupes by event id. *Follow-up: "what if the first request crashed half way?"*
Nothing was committed before the single final transaction, so the retry simply starts again.

## 4. "Why not a distributed transaction?"
*Plain:* it is like asking several banks to all press "confirm" at the same moment, and one of them might be on holiday.
*Deep:* 2PC across databases blocks on coordinator failure and Kafka cannot participate. I use a saga: reserve then post, each a local ACID
step with a compensation (release hold / compensating journal). Pending states are explicit and recovery re-drives stuck steps. The honest
trade-off: clients observe INITIATED/PENDING and the system is eventually consistent between services.

## 5. "What is the outbox pattern and why?"
*Plain:* write the message in the same notebook as the transaction; a courier delivers it afterwards.
*Deep:* dual-write problem: DB commit and Kafka send cannot be atomic. Outbox row commits with the state; relay polls (`SKIP LOCKED`),
sends with acks=all/idempotent producer, marks published. At-least-once; consumers dedupe. CDC (Debezium) is a drop-in replacement for the poller.
*Follow-up: "exactly-once?"* Not across DB + Kafka. It is effectively-once: at-least-once delivery plus idempotent processing.

## 6. "A consumer crashes after processing but before the offset commit?"
Offsets commit only after the listener returns (ack-mode RECORD), and the dedupe marker commits with the business effect. So the record is
redelivered and skipped. If the effect rolled back, the marker rolled back too, so the retry does the work. (`NotificationKafkaIT`.)

## 7. "What happens if the risk service is down?"
Fail closed for money movement: 503, nothing persisted, client retries with the same key. Timeout 2 s, 3 attempts with exponential backoff and jitter,
circuit breaker, bulkhead. Retrying is safe only because the risk call is idempotent on the reference. For reads I could degrade; for writes
a "maybe approved" is never acceptable. A timeout is never turned into success.

## 8. "How is the system secured?"
BCrypt (cost 12) hashes, password policy, constant-time-ish login (dummy hash for unknown users), lockout, short-lived HS256 JWT, validation at the
gateway and again in each service, role + ownership checks (404 for others' resources), internal endpoints need a SERVICE token and are never
routed, no identity headers trusted, CORS closed by default, security headers, secrets only from environment, audit trail, no PII/secrets in logs.
*Own the limits:* shared HS256 secret (use asymmetric keys + mTLS in production), no token revocation, no TLS in the compose setup.

## 9. "How would you scale it?"
Partition by transferRef gives parallelism with per-transfer ordering; stateless services scale horizontally; the relay is safe to run in several
instances (`SKIP LOCKED`); hot accounts serialise by design; reads can move to replicas; ledger could shard by account id range once cross-shard
transfers are handled by the same saga. Bottleneck to watch: lock wait on hot accounts and outbox head-of-line blocking.

## 10. "What would you improve next?" (be candid)
Asymmetric JWT + JWKS, mTLS, DLT re-drive tool, outbox parking for poison rows, idempotency-key expiry, multi-currency FX, ledger statements and
period close, schema registry / Avro, ShedLock-style coordination for the recovery job, contract tests between services, chaos tests in CI.

## Numbers and facts to remember
Spring Boot 3.3.5 + Spring Cloud 2023.0.3, Java 21, `NUMERIC(19,4)`, 3 partitions/topic, 3 retries (0.5s, 1s, 2s) then DLT, risk timeouts 1s/2s,
JWT 15 min, 5 login failures -> 15 min lock, 12-thread/5-slot concurrency test, 5000-step money-conservation property test.
