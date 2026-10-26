-- What the platform believes settled (fed by transfer.completed / transfer.reversed events).
CREATE TABLE expected_transactions (
    transfer_ref  UUID PRIMARY KEY,
    business_date DATE          NOT NULL,           -- UTC date of first completion
    amount        NUMERIC(19,4) NOT NULL,
    currency      VARCHAR(3)    NOT NULL,
    status        VARCHAR(10)   NOT NULL CHECK (status IN ('COMPLETED','REVERSED')),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_expected_business_date ON expected_transactions (business_date);

-- Lines of external settlement files. Duplicate BUSINESS lines are kept (and reported); re-delivery of the same
-- file line is ignored via UNIQUE (batch_id, line_no).
CREATE TABLE settlement_records (
    id              UUID PRIMARY KEY,
    batch_id        VARCHAR(40)   NOT NULL,
    line_no         INT           NOT NULL,
    settlement_ref  VARCHAR(40)   NOT NULL,
    transfer_ref    UUID          NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency        VARCHAR(3)    NOT NULL,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('SETTLED','REVERSED')),
    settlement_date DATE          NOT NULL,
    received_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_settlement_line UNIQUE (batch_id, line_no)
);
CREATE INDEX idx_settlement_batch ON settlement_records (batch_id);

CREATE TABLE reconciliation_runs (
    id               UUID PRIMARY KEY,
    business_date    DATE        NOT NULL,
    batch_id         VARCHAR(40) NOT NULL,
    expected_count   INT         NOT NULL,
    settlement_count INT         NOT NULL,
    matched_count    INT         NOT NULL,
    exception_count  INT         NOT NULL,
    created_by       VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Repeatability: a run for (date, batch) exists at most once; re-requesting it returns the stored result.
    CONSTRAINT uq_run UNIQUE (business_date, batch_id)
);

CREATE TABLE reconciliation_exceptions (
    id             UUID PRIMARY KEY,
    run_id         UUID         NOT NULL REFERENCES reconciliation_runs (id),
    exception_type VARCHAR(30)  NOT NULL,
    transfer_ref   UUID         NOT NULL,
    details        VARCHAR(500) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_exception UNIQUE (run_id, exception_type, transfer_ref)
);
CREATE INDEX idx_exceptions_run ON reconciliation_exceptions (run_id);
CREATE INDEX idx_exceptions_type ON reconciliation_exceptions (exception_type, created_at DESC);
