# Kafka design

## Topics (3 partitions each, replication 1 locally; every topic has a `<topic>.DLT` twin)

| Topic | Key | Producer | Consumer (group) | Meaning |
|---|---|---|---|---|
| `account.opened` | accountId | account | ledger (`ledger-service`) | create the ledger projection of an account |
| `account.status.changed` | accountId | account | ledger | FROZEN/ACTIVE/CLOSED affects posting rules |
| `transfer.initiated` | transferRef | transaction | ledger | command: reserve funds |
| `ledger.funds.reserved` / `ledger.funds.rejected` | transferRef | ledger | transaction (`transaction-service`) | outcome of reservation |
| `ledger.posting.requested` | transferRef | transaction | ledger | command: post the journal |
| `ledger.posting.completed` / `ledger.posting.failed` | transferRef | ledger | transaction | outcome of posting |
| `transfer.reversal.requested` | transferRef | transaction | ledger | command: post a compensating journal |
| `ledger.reversal.completed` / `ledger.reversal.failed` | transferRef | ledger | transaction | outcome of reversal |
| `transfer.completed` / `transfer.failed` / `transfer.reversed` | transferRef | transaction | notification (`notification-service`), reconciliation (`reconciliation-service`; completed + reversed) | business outcomes |
| `settlement.received` | batchId | (external simulator / admin tooling) | reconciliation | one settlement-file line |

Deviations from the brief's example list: risk is a synchronous, fail-closed call (a transfer must not start without a
decision), so there is no `risk.decision.completed` topic (the decision is persisted and auditable in `risk_db`);
`notification.requested` is unnecessary because the notification service reacts directly to the outcome events.

## Envelope (schemaVersion 1)

```json
{ "eventId": "uuid", "eventType": "ledger.funds.reserved", "aggregateId": "<transferRef>",
  "correlationId": "uuid-or-client-id", "occurredAt": "2026-10-19T10:00:00Z", "schemaVersion": 1,
  "payload": { "transferRef": "uuid" } }
```
Payload records live in `platform-commons/.../contracts/Events.java`. Unknown fields are ignored (add-only evolution).
Money in payloads is a JSON number parsed with `USE_BIG_DECIMAL_FOR_FLOATS` (never a double).

## Partitioning and ordering

The key is the **transfer reference** for every transfer-related topic, so all events of one transfer land on one
partition of each topic and are consumed in order. **Ordering is only guaranteed per key per topic.** There is no
ordering between topics (e.g. `account.opened` vs `transfer.initiated`): handlers are therefore written to be
order-tolerant: the saga ignores events that do not match the current state, and the ledger throws a *retryable*
`AccountNotYetKnownException` if an account projection has not arrived yet.

## Producer: transactional outbox

Business code calls `OutboxPublisher.publish()` inside its DB transaction (`Propagation.MANDATORY`). A scheduled
`OutboxRelay` reads unpublished rows ordered by `seq` (`FOR UPDATE SKIP LOCKED`), sends with `acks=all` and
`enable.idempotence=true`, waits for the broker ack, then marks the row published.

* Broker down -> send fails -> row stays, `attempts`/`last_error` updated, metric `fincore_outbox_publish_failures_total`,
  gauge `fincore_outbox_pending` rises. When the broker returns, the same rows are published.
* Crash after the broker ack but before `published_at` is committed -> the row is sent again after restart.
  **Delivery is at-least-once**; consumers dedupe on `eventId`.
* The relay stops a batch at the first failure so per-key order is preserved. Known limit: a permanently poisonous row
  would block the queue (head-of-line); the alert on `fincore_outbox_pending` is the safety net.
* CDC-ready: the table is shaped for a Debezium outbox connector, which can replace the poller unchanged.

## Consumer: idempotency, acknowledgement, retries, DLT

* `enable.auto.commit=false`, listener `ack-mode=record`: an offset is committed only after the listener returned, i.e.
  **after** the database transaction committed. A crash before the commit means redelivery, never loss.
* `EventProcessor` inserts `(event_id, consumer)` into `processed_events` **in the same transaction** as the business
  effect. A redelivered event finds the marker and is skipped (`fincore_events_duplicates_total`). If the business
  effect rolls back the marker rolls back too, so a retry is processed normally.
* `DefaultErrorHandler`: 3 redeliveries with exponential backoff (500 ms, 1 s, 2 s), then
  `DeadLetterPublishingRecoverer` publishes to `<topic>.DLT` (same partition, exception details in headers) and the
  offset is committed so one poison message cannot block the partition. `InvalidEventException` (malformed JSON, wrong
  payload shape) is **not retryable** and is dead-lettered immediately.
* Metrics: `fincore_kafka_consumer_retries_total`, `fincore_kafka_dlt_published_total`; consumer lag via
  `kafka-exporter` (`kafka_consumergroup_lag`, Grafana panel "Consumer group lag").
* Re-driving a DLT message is a manual operation in this simulation (no tooling provided).

## What "exactly once" means here, and what it does not

Kafka's idempotent producer and transactions give exactly-once *within Kafka*. They cannot make a write to **PostgreSQL**
and a write to Kafka atomic. FinCore therefore achieves **effectively-once processing** with
*at-least-once delivery + idempotent consumers* (outbox on the way out, `processed_events` plus natural-key uniqueness on
the way in). Kafka is the transport, **never the source of truth**: balances live in `ledger_db`, and every command can
be re-driven from the database state.

## Demonstrations

* `scripts/demo-outbox-crash.sh`: broker stopped, transfer still accepted, event waits in the outbox, completes after restart.
* `scripts/demo-dlt.sh`: delivery that always fails -> 3 retries -> DLT with failure headers; malformed message -> DLT immediately.
* `NotificationKafkaIT`, `OutboxRelayIT`: the same behaviours as automated Testcontainers tests.
