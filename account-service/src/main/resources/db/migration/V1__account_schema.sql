CREATE TABLE accounts (
    id                 UUID PRIMARY KEY,
    customer_id        UUID          NOT NULL,
    account_number     VARCHAR(12)   NOT NULL UNIQUE,
    currency           VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    per_transfer_limit NUMERIC(19,4) NOT NULL CHECK (per_transfer_limit > 0),
    daily_limit        NUMERIC(19,4) NOT NULL CHECK (daily_limit >= per_transfer_limit),
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_customer ON accounts (customer_id);
-- Balances are deliberately NOT here: the ledger service is the only source of truth for money.
