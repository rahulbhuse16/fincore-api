# Mandatory failure scenarios: what happens and how to see it

| # | Scenario | Behaviour | Evidence |
|---|---|---|---|
| 1 | Client retries the same transfer | Same `Idempotency-Key` + body -> **200**, same transfer, `Idempotent-Replay: true`, no second effect. Different body -> 422. | `TransferIdempotencyIT`, `demo-transfer.sh` steps 4-5 |
| 2 | Two requests spend the same funds | Row lock serialises them; the second sees the reduced available balance and is rejected `INSUFFICIENT_FUNDS`. | `LedgerIT.concurrentReservations...` (12 threads vs 5 slots), `demo-transfer.sh` step 6 |
| 3 | Kafka broker unavailable | Transfer is still accepted (event is in the outbox, same TX). Relay retries; nothing is reported as sent. After recovery the same rows are published. | `OutboxRelayIT.brokerOutage...`, `scripts/demo-outbox-crash.sh` |
| 4 | Consumer processed the event but crashed before acknowledging | Offset not committed -> redelivery -> `processed_events` marker (committed with the effect) makes it a no-op. | `EventProcessor`, `NotificationKafkaIT.duplicateDelivery...` |
| 5 | Event delivered more than once | Skipped via `processed_events`; ledger commands additionally idempotent on `transfer_ref`. | `LedgerIT.duplicateCommands...`, metric `fincore_events_duplicates_total` |
| 6 | Ledger posting fails | Legit business failure (destination closed): hold released in the same TX, `posting.failed` -> transfer `FAILED`. Technical failure: TX rolls back, retries, DLT; recovery job re-drives the command later. Funds are neither lost nor duplicated. | `LedgerPostingService.postTransfer`, `StuckTransferRecovery` |
| 7 | Notification fails after completion | Independent consumer; the transfer is already COMPLETED. Retries, then DLT. | `demo-dlt.sh`, `NotificationKafkaIT.persistentDeliveryFailure...` |
| 8 | Crash after DB commit, before event publish | Cannot lose the event: it is a row committed with the state. The relay publishes it after restart (at-least-once). | `OutboxRelayIT.crashBetweenPublish...` |
| 9 | Transfer reversed | Compensating journal with flipped lines, linked to the original; original rows untouched; idempotent; refused if the receiver already spent the money. | `LedgerIT.reversal...`, `demo-transfer.sh` step 9 |
| 10 | Downstream times out | Explicit 1 s connect / 2 s read timeouts. Reads: 3 attempts, exponential backoff + jitter, circuit breaker. **Never a false success**: the caller gets 503 and retries with the same key. The risk call is a POST but idempotent on `referenceId`, which is why retrying it is safe. | `RiskClient`, `AccountClient`, `application.yml` (resilience4j) |

## Risk-service outage policy

| Operation | Policy | Why |
|---|---|---|
| Creating a transfer | **Fail closed**: 503 `risk-unavailable`, nothing persisted | moving money without a fraud decision is worse than a retry; the retry is safe (same key) |
| Opening an account (KYC lookup) | **Fail closed**: 503 | never open an account for an unverified customer |
| Reads (history, balances) | not dependent on risk | - |
| Gateway Redis down | rate limiter **fails open** | availability of the bank beats rate limiting; login brute force is separately limited by account lockout |

## Other behaviours worth knowing
* Gateway never retries a write (`Retry` filter is GET-only); the circuit-breaker fallback answers 503, not a fake success.
* Recovery never fails a transfer on a timeout, it re-drives (see transfer-workflow.md).
* Risk REVIEW holds the transfer without reserving funds; admin decision continues or ends it.
