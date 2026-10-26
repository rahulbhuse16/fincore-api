# Architecture decision records

Format: **Context -> Decision -> Consequences**. Each ADR has a plain-language summary first.

## ADR-1 Double-entry ledger
*Plain:* every movement of money is written as two equal and opposite lines, so the books can always be checked: if
debits and credits do not match, something is wrong. *Context:* a single `balance` column that gets `+=`/`-=` cannot be
audited or reconciled and silently loses history. *Decision:* `journal_transactions` + `ledger_entries`; balances are
derived state kept in `ledger_accounts` and updated in the same transaction. Balance and immutability are enforced by
database triggers, not just by code. Corrections are compensating journals. *Consequences:* more writes per transfer
and a more complex model; in exchange, audits, reversals and reconciliation are straightforward and tamper-evident.

## ADR-2 Each service owns its data
*Plain:* each service has its own database and password; it cannot read anyone else's tables. *Context:* shared tables
create hidden coupling and make independent deployment impossible. *Decision:* one database and role per service;
information crosses boundaries only through APIs and events; e.g. the ledger keeps its own projection of accounts built from
`account.opened`. *Consequences:* no joins across services and eventual consistency between them (see ADR-6), but each
service can change its schema freely and failures are contained.

## ADR-3 Transactional outbox
*Plain:* we save "the thing happened" and "tell everyone" in the same database transaction; a separate worker delivers the
message. *Context:* `commit(); kafka.send()` can crash in between (state changed, nobody told) and `kafka.send(); commit()`
can announce something that then rolls back. *Decision:* `outbox_events` written via `OutboxPublisher` (`MANDATORY`
propagation) and drained by `OutboxRelay`. *Consequences:* at-least-once delivery (duplicates possible, so consumers must be
idempotent), slight added latency (poll interval 500 ms), and a table to monitor; no lost events.

## ADR-4 Idempotency
*Plain:* if the client retries because it did not hear back, the bank must not pay twice. *Decision:* mandatory
`Idempotency-Key`; deterministic transfer id from `(customer, key)`; request fingerprint to detect misuse; UNIQUE
constraint as the arbiter of races; the same reference flows to risk and ledger, which deduplicate on it; consumers dedupe on
`eventId`. *Consequences:* keys are retained with the transfer (no expiry job in this version); clients must generate unique keys.

## ADR-5 Concurrent-spend protection
*Plain:* two simultaneous payments must not both use the same money. *Decision:* pessimistic row locks
(`FOR UPDATE ORDER BY id`) around read-check-write of balances, plus `CHECK` constraints. *Alternatives rejected:*
optimistic locking alone (works but needs retry loops under contention on hot accounts), `SERIALIZABLE` isolation
(heavier, and still needs retries), distributed locks (extra failure mode). *Consequences:* a hot account serialises its
transfers (correct and simple); lock ordering prevents deadlocks.

## ADR-6 No distributed transactions; choreographed saga
*Plain:* we do not try to make several databases commit "at the same instant"; we do steps that can each be undone.
*Context:* 2PC across Postgres instances and Kafka is slow, fragile and unsupported by Kafka consumers. *Decision:* the
transfer is a saga (reserve -> post), each step a local ACID transaction with an explicit compensation (release hold,
compensating journal). Pending states are modelled and visible; a recovery job re-drives stuck steps. *Consequences:*
clients see `INITIATED/PENDING` and must poll; invariants are proven per step instead of globally at an instant.

## ADR-7 Retries and dead-letter topics
*Plain:* retry what might work next time; set aside what never will, without blocking the line. *Decision:* 3 retries with
exponential backoff, then `<topic>.DLT` with failure headers; malformed messages skip retries; never retry non-idempotent
writes without an idempotency strategy. *Consequences:* DLT messages need an operator (no auto-redrive tool yet).

## ADR-8 Synchronous risk check, fail-closed
*Decision:* risk is an HTTP call made before the transfer exists, because the decision gates whether money may move.
Timeouts/open circuit mean 503, never an implicit approval. The call is idempotent on the transfer reference so
retries cannot double count. *Alternative:* async risk would need a PENDING_RISK state and complicates the happy path.

## ADR-9 Gateway as first line, services as last line
Gateway validates JWT, rate-limits, correlates, strips spoofable headers, denies `/internal/**`. Services validate again and
own authorization (role + ownership). Identity is carried only by the verified JWT, never by headers.

## ADR-10 HS256 shared secret for JWTs (simplification)
A symmetric key is easy to run locally but means any service could mint any token. Production should use RS256/ES256 with
a JWKS endpoint (only identity holds the private key) and mTLS or workload identity for service-to-service calls.
The secret comes only from `JWT_SECRET`; services refuse to start with a short or placeholder value.

## ADR-11 A small technical shared module (`platform-commons`)
Security config, problem details, outbox, idempotent consumer and event records are identical in every service and
security-sensitive; copy-paste drift is the bigger risk. Rule: **no business logic, no entities, no repositories** in it.
Bounded contexts keep their own `Money`-like types and DTOs.

## ADR-12 Domain code is framework-free
`domain/` packages import only the JDK. This keeps the financial rules unit-testable in milliseconds and independent
of Spring. `scripts/verify-domain.sh` compiles and tests them with nothing but a JDK and JUnit.
