# API catalog

Base URL (through the gateway): `http://localhost:8080`. All bodies are JSON. Errors are RFC 9457 `application/problem+json`:

```json
{ "type": "https://fincore.example/problems/insufficient-funds", "title": "Unprocessable Entity", "status": 422,
  "detail": "...", "code": "insufficient-funds", "correlationId": "…", "timestamp": "…", "errors": [{"field":"amount","message":"…"}] }
```
Common responses: **400** validation (`errors[]`), **401** missing/invalid/expired token, **403** role not allowed, **404** not found *or not yours*
(never a hint about other customers' resources), **409** conflict, **422** business rule violated, **429** rate limited, **503** dependency unavailable (nothing was changed).
Interactive docs per service: `http://localhost:<port>/swagger-ui.html` (ports 8081-8088 are bound to 127.0.0.1 in the dev compose file).

## Identity (public; 2 req/s burst 5 per client IP at the gateway)

| API | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /api/v1/auth/register` | none | `{"username":"alice","email":"alice@example.test","password":"Str0ng!Passw0rd"}` username `[A-Za-z0-9_.-]{3,50}`, valid email, password per policy (12-72 chars, upper, lower, digit, symbol, not containing username) | **201** `{"userId","customerId","username"}` | 400 `validation-failed`/`weak-password`; 409 `already-registered` (same message for username/email) |
| `POST /api/v1/auth/login` | none | `{"username","password"}` | **200** `{"accessToken","tokenType":"Bearer","expiresIn":900}` | 401 `invalid-credentials` (identical for unknown user, bad password, locked; 5 failures lock 15 min) |

## Customers

| API | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /api/v1/customers` | CUSTOMER (creates **own** profile; id = token's `customer_id`) | `{"fullName","email","phone":"+919876543210","dateOfBirth":"1990-05-01","documentType":"PASSPORT\|NATIONAL_ID\|DRIVING_LICENCE","documentNumber":"ABCDE1234F"}` DOB in the past; document 6-30 chars | **201** profile with `kycStatus` (VERIFIED, REJECTED, or PENDING if document starts `MANUAL`; `FAIL…` or under-18 -> REJECTED) and masked `documentLast4` | 400; 403 not a customer; 409 `customer-exists`/`email-in-use` |
| `GET /api/v1/customers/{id}` | the customer, or ADMIN | - | **200** | 404 (also for other customers' ids) |
| `PATCH /api/v1/customers/{id}/kyc` | ADMIN | `{"status":"VERIFIED\|REJECTED\|PENDING","reason":"…"}` | **200** | 403; 404; 409 `invalid-kyc-transition` (PENDING->VERIFIED/REJECTED, VERIFIED->REJECTED, REJECTED->PENDING only) |

## Accounts

| API | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /api/v1/accounts` | CUSTOMER (own) / ADMIN (may pass `customerId`) | `{"currency":"INR"}` (INR, USD) | **201** `{"accountId","customerId","accountNumber":"FC…","currency","status":"ACTIVE","perTransferLimit":50000,"dailyLimit":200000}` | 403; 409 `account-limit-reached` (5 max); 422 `kyc-not-verified`, `customer-profile-missing`, `unsupported-currency`; 503 `customer-service-unavailable` (fail-closed) |
| `GET /api/v1/accounts/{id}` | owner / ADMIN | - | **200** | 404 |
| `GET /api/v1/accounts/{id}/balance` | owner / ADMIN (served by the ledger via gateway rewrite) | - | **200** `{"accountId","currency","ledgerBalance","availableBalance","heldAmount","status","asOf"}` | 404 |
| `GET /api/v1/customers/{id}/accounts` | the customer / ADMIN | - | **200** array of accounts | 404 |
| `PATCH /api/v1/accounts/{id}/status` | ADMIN | `{"status":"FROZEN\|ACTIVE\|CLOSED"}` | **200** (emits `account.status.changed`) | 409 `invalid-account-transition` |
| `PATCH /api/v1/accounts/{id}/limits` | ADMIN | `{"perTransferLimit":10000,"dailyLimit":50000}` (daily >= per-transfer) | **200** | 422 `invalid-limits` |

## Transfers

| API | Auth | Request | Success | Errors |
|---|---|---|---|---|
| `POST /api/v1/transfers` | CUSTOMER; header **`Idempotency-Key`** (8-64 chars `[A-Za-z0-9_-]`) | `{"sourceAccountId","destinationAccountId","amount":1000.00,"currency":"INR","description":"rent"}` amount >= 0.01, max 2 decimals, description <= 140 | **202** + `Location`, body `{"transferId","status":"INITIATED"\|"PENDING_REVIEW"\|"FAILED",…}`; **200** + `Idempotent-Replay: true` for a replay | 400 `invalid-idempotency-key`/validation; 404 `source-account-not-found` (incl. someone else's account); 422 `idempotency-key-reuse`, `destination-account-not-found`, `same-account`, `currency-mismatch`, `source-not-active`, `per-transfer-limit-exceeded`, `daily-limit-exceeded`, `amount-precision`; 503 `risk-unavailable`/`account-service-unavailable` |
| `GET /api/v1/transfers/{id}` | initiator, receiving customer, ADMIN | - | **200** (poll until `COMPLETED`/`FAILED`; `failureReason` e.g. `INSUFFICIENT_FUNDS`, `RISK_REJECTED`) | 404 |
| `GET /api/v1/accounts/{id}/transactions?page=0&size=20&sort=createdAt,desc` | owner / ADMIN | sort in `createdAt|amount|status`, size 1-100 | **200** `{"content":[…],"page","size","totalElements","totalPages"}` | 400 `invalid-sort`/`invalid-page-size` |
| `POST /api/v1/transfers/{id}/reverse` | ADMIN | `{"reason":"customer dispute"}` | **202** status `REVERSING` (idempotent; `REVERSED` when the ledger confirms) | 404; 409 `transfer-not-reversible` |
| `POST /api/v1/transfers/{id}/review` | ADMIN | `{"decision":"APPROVE\|REJECT","note":"…"}` | **200** | 409 `transfer-not-in-review` |

## Ledger

| API | Auth | Notes |
|---|---|---|
| `GET /api/v1/ledger/accounts/{id}/entries?page&size&sort=createdAt,asc\|desc` | owner / ADMIN | paginated entries with `direction`, `amount`, `ledgerBalanceAfter` |
| `GET /api/v1/ledger/journals/{id}` | ADMIN sees all lines; a customer sees only lines on their own accounts | 404 otherwise |
| `POST /api/v1/ledger/deposits` | ADMIN | `{"reference":"uuid","accountId":"uuid","amount":5000.00,"currency":"INR"}` **201** `{"journalId","replay":false}`; same `reference` -> **200** `replay:true` (idempotent); 422 `account-not-eligible`/`unsupported-currency` |

## Risk (ADMIN or internal SERVICE only; routed for ADMIN)

| API | Notes |
|---|---|
| `POST /api/v1/risk/assessments` | `{"referenceId","customerId","sourceAccountId","destinationAccountId","amount","currency"}` -> **201** (or **200** replay) `{"assessmentId","decision":"APPROVE\|REVIEW\|REJECT","score","reasons":["VELOCITY_HIGH"],"rulesVersion"}` |
| `GET /api/v1/risk/assessments/{id}` | stored decision (audit) |

Rules and defaults (configurable under `fincore.risk.*`): single transfer > 500,000 REJECT; > 100,000 REVIEW; rolling 24h total > 1,000,000 REJECT;
>= 5 transfers in 10 min REVIEW, >= 10 REJECT; same amount to same destination >= 3 times in 1 h REVIEW.

## Reconciliation (ADMIN only)

| API | Notes |
|---|---|
| `POST /api/v1/reconciliation/settlement-batches` | `{"batchId","settlementDate","lines":[{"lineNo":1,"settlementRef","transferRef","amount","currency","status":"SETTLED\|REVERSED"}]}` idempotent per (batchId, lineNo) -> `{"received","newlyStored"}` |
| `POST /api/v1/reconciliation/runs` | `{"businessDate":"2026-10-19","batchId":"…"}` **201** new / **200** existing (repeatable, no duplicate exceptions); 422 `batch-not-found` |
| `GET /api/v1/reconciliation/runs/{id}` | counts |
| `GET /api/v1/reconciliation/exceptions?runId&type&page&size` | `MISSING_SETTLEMENT`, `UNEXPECTED_SETTLEMENT`, `DUPLICATE_SETTLEMENT`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH` |

## Notifications
`GET /api/v1/notifications?page&size`: CUSTOMER sees their own simulated messages.

## Internal (SERVICE role, not routed by the gateway)
`GET /internal/v1/accounts/{id}/transfer-view` (account), `GET /internal/v1/customers/{id}/kyc-status` (customer).

## Rate limits at the edge
API routes 20 req/s burst 40 per authenticated subject; auth routes 2 req/s burst 5 per client IP (HTTP 429 with `X-RateLimit-*` headers from the Redis limiter).
