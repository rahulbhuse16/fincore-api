-- Technical tables shared by convention (each service owns its OWN copy in its OWN database).

-- Transactional outbox: written in the same DB transaction as the business change.
CREATE TABLE outbox_events (
    seq          BIGSERIAL PRIMARY KEY,
    event_id     UUID         NOT NULL UNIQUE,
    topic        VARCHAR(120) NOT NULL,
    event_key    VARCHAR(120) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

-- Idempotent-consumer ledger of handled events.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(80)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- Append-only audit trail of sensitive actions. Never contains secrets or tokens.
CREATE TABLE audit_events (
    id            UUID PRIMARY KEY,
    actor         VARCHAR(100) NOT NULL,
    action        VARCHAR(80)  NOT NULL,
    resource_type VARCHAR(60)  NOT NULL,
    resource_id   VARCHAR(100),
    outcome       VARCHAR(20)  NOT NULL,
    detail        VARCHAR(500),
    correlation_id VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_resource ON audit_events (resource_type, resource_id, created_at);
CREATE INDEX idx_audit_actor ON audit_events (actor, created_at);
