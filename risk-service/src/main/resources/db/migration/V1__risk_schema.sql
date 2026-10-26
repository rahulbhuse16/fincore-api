CREATE TABLE risk_assessments (
    id                     UUID PRIMARY KEY,
    reference_id           UUID          NOT NULL UNIQUE,    -- idempotency: one assessment per transfer reference
    customer_id            UUID          NOT NULL,
    source_account_id      UUID          NOT NULL,
    destination_account_id UUID          NOT NULL,
    amount                 NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3)    NOT NULL,
    decision               VARCHAR(10)   NOT NULL CHECK (decision IN ('APPROVE','REVIEW','REJECT')),
    score                  INT           NOT NULL,
    reasons                VARCHAR(500)  NOT NULL DEFAULT '',   -- comma-separated rule names (audit trail)
    rules_version          VARCHAR(20)   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_risk_source_time ON risk_assessments (source_account_id, created_at DESC);
