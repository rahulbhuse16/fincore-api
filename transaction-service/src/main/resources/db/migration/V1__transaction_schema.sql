CREATE TABLE transfers (
    id                      UUID PRIMARY KEY,                 -- deterministic reference derived from (customer, idempotency key)
    customer_id             UUID          NOT NULL,           -- initiator = owner of the source account
    idempotency_key         VARCHAR(64)   NOT NULL,
    request_fingerprint     VARCHAR(64)   NOT NULL,
    source_account_id       UUID          NOT NULL,
    destination_account_id  UUID          NOT NULL,
    destination_customer_id UUID          NOT NULL,
    amount                  NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency                VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    description             VARCHAR(140),
    status                  VARCHAR(16)   NOT NULL CHECK (status IN
        ('PENDING_REVIEW','INITIATED','PENDING','COMPLETED','FAILED','REVERSING','REVERSED')),
    failure_reason          VARCHAR(80),
    risk_decision           VARCHAR(10),
    risk_assessment_id      UUID,
    reversal_reason         VARCHAR(200),
    reversal_failure_reason VARCHAR(80),
    recovery_attempts       INT           NOT NULL DEFAULT 0,
    last_recovery_at        TIMESTAMPTZ,
    needs_attention         BOOLEAN       NOT NULL DEFAULT FALSE,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ,
    reversed_at             TIMESTAMPTZ,
    -- THE idempotency guarantee: two concurrent identical requests cannot both insert.
    CONSTRAINT uq_transfer_idempotency UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX idx_transfers_source_time ON transfers (source_account_id, created_at DESC);
CREATE INDEX idx_transfers_dest_time   ON transfers (destination_account_id, created_at DESC);
CREATE INDEX idx_transfers_status_updated ON transfers (status, updated_at);

CREATE TABLE transfer_state_history (
    id          UUID PRIMARY KEY,
    transfer_id UUID         NOT NULL REFERENCES transfers (id),
    from_status VARCHAR(16),
    to_status   VARCHAR(16)  NOT NULL,
    reason      VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_history_transfer ON transfer_state_history (transfer_id, created_at);
