# Database design

One PostgreSQL 16 server, **one database and one login role per service** (`<svc>_db` / `<svc>_user`, created by
`infrastructure/postgres/init-databases.sh`). A service has no credentials for any other database, so cross-service table
access is impossible, not merely discouraged. Schemas are created by **Flyway** (`ddl-auto=validate` in every service).
Every service additionally runs `db/platform/V1000__platform_tables.sql` which creates its own `outbox_events`,
`processed_events` and `audit_events` tables.

Money: `NUMERIC(19,4)` everywhere, `BigDecimal` in Java. Timestamps: `TIMESTAMPTZ` (UTC). Primary keys: UUID.

| Database | Tables (key constraints) |
|---|---|
| identity_db | `users` (UNIQUE username, email; bcrypt hash; role CHECK; lockout columns) |
| customer_db | `customers` (id = identity's customer_id claim; UNIQUE email; only `document_last4` stored; KYC CHECK) |
| account_db | `accounts` (UNIQUE account_number; CHECK `daily_limit >= per_transfer_limit`; no balance column) |
| transaction_db | `transfers` (id = deterministic reference; **UNIQUE (customer_id, idempotency_key)**; status CHECK), `transfer_state_history` |
| ledger_db | `ledger_accounts` (CHECK `available >= 0` and `available <= ledger`), `ledger_holds` (UNIQUE transfer_ref), `journal_transactions` (UNIQUE (reference, type), UNIQUE reverses_journal_id), `ledger_entries` (composite FK keeps currency consistent) + immutability and balance triggers |
| risk_db | `risk_assessments` (UNIQUE reference_id; append-only audit of each decision with rule names) |
| notification_db | `notification_records` (UNIQUE (event_id, recipient, channel)) |
| reconciliation_db | `expected_transactions`, `settlement_records` (UNIQUE (batch_id, line_no)), `reconciliation_runs` (UNIQUE (business_date, batch_id)), `reconciliation_exceptions` (UNIQUE (run_id, type, transfer_ref)) |

## Indexes worth noting
`transfers(source_account_id, created_at DESC)` and `(destination_account_id, created_at DESC)` for history;
`transfers(status, updated_at)` for the recovery scan; `ledger_entries(account_id, created_at DESC, id DESC)` for statements;
partial index `outbox_events(seq) WHERE published_at IS NULL` keeps the relay scan cheap however large the table grows.

## How available balances are protected against concurrent spending
1. `reserveFunds` runs in one transaction and first executes `SELECT ... FROM ledger_accounts WHERE id IN (...) ORDER BY id FOR UPDATE`.
2. A concurrent request for the same account blocks on that row lock until the first commits (READ COMMITTED then re-reads the
   committed, already-reduced balance), so the check `available >= amount` is never evaluated against stale data.
3. Deterministic lock ordering (`ORDER BY id`) prevents deadlocks between opposite-direction transfers.
4. `CHECK (available_balance >= 0)` rejects the write even if application logic were wrong.
Optimistic `@Version` columns exist as an extra guard for writers that do not take the lock (e.g. status changes).

## Transactions boundaries
`@Transactional` lives on application services (never on controllers or repositories). Remote calls (accounts, risk, KYC) are made
**outside** any DB transaction (the orchestrating services are deliberately not `@Transactional`), so a slow dependency cannot hold
database locks or connections.
