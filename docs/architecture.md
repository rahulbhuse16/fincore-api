# FinCore architecture

FinCore is a **simulation** of a digital bank. No real money, customers or regulators are involved.

```mermaid
flowchart LR
  client([Client]) --> gw[API Gateway<br/>JWT check, Redis rate limit,<br/>correlation id, circuit breakers]
  gw --> id[Identity<br/>users, bcrypt, JWT]
  gw --> cu[Customer<br/>profile, simulated KYC]
  gw --> ac[Account<br/>account master data, limits]
  gw --> tx[Transaction<br/>transfer state machine,<br/>idempotency, saga]
  gw --> le[Ledger<br/>double entry, balances, holds]
  gw --> ri[Risk<br/>rules engine]
  gw --> no[Notification]
  gw --> re[Reconciliation]
  tx -- sync REST, fail-closed --> ri
  tx -- sync REST read --> ac
  ac -- sync REST read --> cu
  ac -. account.opened .-> K[(Kafka)]
  tx <-. commands and outcomes .-> K
  le <-. commands and outcomes .-> K
  K -. transfer.completed/failed/reversed .-> no
  K -. transfer.completed/reversed, settlement.received .-> re
  id --- dbi[(identity_db)]
  cu --- dbc[(customer_db)]
  ac --- dba[(account_db)]
  tx --- dbt[(transaction_db)]
  le --- dbl[(ledger_db)]
  ri --- dbr[(risk_db)]
  no --- dbn[(notification_db)]
  re --- dbre[(reconciliation_db)]
  gw --- redis[(Redis)]
```

## Service ownership

| Service | Owns | Notes |
|---|---|---|
| identity | users, password hashes, login lockout | issues HS256 JWTs (claims: `sub`, `roles`, `customer_id`) |
| customer | profile, KYC status | KYC is a deterministic simulation (`KycSimulator`) |
| account | account number, owner, status, limits | **no balances**: money lives only in the ledger |
| transaction | transfers, state history, idempotency keys | orchestrates the saga, never touches balances |
| ledger | accounts' balances, holds, journals, entries | the only source of truth for money |
| risk | assessments (audit trail) | deterministic rules, idempotent on the transfer reference |
| notification | notification records | consumes outcome events only |
| reconciliation | expected transactions, settlement lines, runs, exceptions | repeatable runs |
| api-gateway | nothing persistent (Redis counters) | edge only |

`platform-commons` contains **technical** plumbing (security, problem details, outbox, idempotent consumer, DLT policy,
audit) and the Kafka event records. It contains no business rules. Each service has its own entities, DTOs and
migrations; nothing is shared at the data level.

## Request path and trust

1. The gateway validates the JWT, applies a Redis token bucket, assigns `X-Correlation-Id`, strips client-supplied
   identity headers, and forwards the **original** `Authorization` header.
2. Every service re-validates the JWT (same secret, issuer and expiry) and authorizes the request itself
   (role + customer-level ownership). A request that bypasses the gateway is still protected.
3. `/internal/**` endpoints require the `SERVICE` role and are never routed by the gateway (`denyAll` at the edge).
   Services mint 2-minute service tokens with the shared secret (a deliberate simplification, see ADR-10).

## Package layout inside a service

```
api/           controllers, DTO records, Kafka listeners (thin)
application/   transactional use-case services, saga, recovery jobs
domain/        PURE Java: rules, state machines, value objects (no Spring, no I/O) <- unit tested without a framework
persistence/   JPA entities and repositories
client/        outbound HTTP with timeouts, retries, circuit breakers
```
