-- Ledger service schema. Money is NUMERIC(19,4); never floating point.

CREATE TABLE ledger_accounts (
    id                 UUID PRIMARY KEY,                       -- equals the account-service account id
    owner_customer_id  UUID,                                   -- NULL for system accounts
    currency           VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    kind               VARCHAR(10)   NOT NULL CHECK (kind IN ('LIABILITY', 'ASSET')),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    ledger_balance     NUMERIC(19,4) NOT NULL DEFAULT 0,
    available_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- Last line of defence: even a buggy service cannot overspend or hold more than it owns.
    CONSTRAINT chk_available_non_negative CHECK (available_balance >= 0),
    CONSTRAINT chk_available_le_ledger    CHECK (available_balance <= ledger_balance),
    CONSTRAINT uq_account_currency UNIQUE (id, currency)
);
CREATE INDEX idx_ledger_accounts_owner ON ledger_accounts (owner_customer_id);

-- System cash-clearing accounts (ASSET kind) used as the counter-party of simulated deposits.
INSERT INTO ledger_accounts (id, owner_customer_id, currency, kind, status)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'INR', 'ASSET', 'ACTIVE'),
       ('00000000-0000-0000-0000-000000000002', NULL, 'USD', 'ASSET', 'ACTIVE');

-- A hold reserves funds for exactly one transfer. transfer_ref is unique => reservation is idempotent.
CREATE TABLE ledger_holds (
    id              UUID PRIMARY KEY,
    transfer_ref    UUID          NOT NULL UNIQUE,
    source_account_id      UUID   NOT NULL REFERENCES ledger_accounts (id),
    destination_account_id UUID   NOT NULL REFERENCES ledger_accounts (id),
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)    NOT NULL,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('HELD', 'POSTED', 'RELEASED', 'REJECTED')),
    reason_code     VARCHAR(60),
    journal_id      UUID,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_holds_source ON ledger_holds (source_account_id, status);

CREATE TABLE journal_transactions (
    id                  UUID PRIMARY KEY,
    journal_type        VARCHAR(10)  NOT NULL CHECK (journal_type IN ('TRANSFER', 'REVERSAL', 'DEPOSIT')),
    reference           UUID         NOT NULL,                  -- transfer ref / deposit reference
    reverses_journal_id UUID REFERENCES journal_transactions (id),
    currency            VARCHAR(3)   NOT NULL,
    description         VARCHAR(200),
    posted_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Idempotent posting: one journal per (reference, type). A retry can never post twice.
    CONSTRAINT uq_journal_reference_type UNIQUE (reference, journal_type),
    -- A journal can be reversed at most once.
    CONSTRAINT uq_journal_reverses UNIQUE (reverses_journal_id)
);

CREATE TABLE ledger_entries (
    id                UUID PRIMARY KEY,
    journal_id        UUID          NOT NULL REFERENCES journal_transactions (id),
    account_id        UUID          NOT NULL,
    direction         VARCHAR(6)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount            NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3)    NOT NULL,
    ledger_balance_after NUMERIC(19,4) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- An entry's currency must equal its account's currency.
    CONSTRAINT fk_entry_account_currency FOREIGN KEY (account_id, currency) REFERENCES ledger_accounts (id, currency)
);
CREATE INDEX idx_entries_account_time ON ledger_entries (account_id, created_at DESC, id DESC);
CREATE INDEX idx_entries_journal ON ledger_entries (journal_id);

-- ---------------------------------------------------------------------------------------------
-- IMMUTABILITY: posted journals and entries can never be updated or deleted. Corrections are new
-- (compensating) journals. This is enforced in the database, not just by application convention.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION prevent_ledger_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger table % is append-only; post a compensating journal instead', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();
CREATE TRIGGER trg_journals_immutable BEFORE UPDATE OR DELETE ON journal_transactions
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_mutation();
CREATE TRIGGER trg_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();
CREATE TRIGGER trg_journals_no_truncate BEFORE TRUNCATE ON journal_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION prevent_ledger_mutation();

-- ---------------------------------------------------------------------------------------------
-- DOUBLE ENTRY: at COMMIT time, every journal must have total debits = total credits and >= 2 lines.
-- DEFERRED so the entries of one journal can be inserted one by one inside a single transaction.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION assert_journal_balanced() RETURNS trigger AS $$
DECLARE
    debits  NUMERIC;
    credits NUMERIC;
    n       INT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO debits, credits, n
      FROM ledger_entries WHERE journal_id = NEW.journal_id;
    IF debits <> credits OR n < 2 THEN
        RAISE EXCEPTION 'journal % is unbalanced (debits=%, credits=%, lines=%)', NEW.journal_id, debits, credits, n
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_journal_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION assert_journal_balanced();
