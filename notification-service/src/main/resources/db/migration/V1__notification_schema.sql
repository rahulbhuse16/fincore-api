CREATE TABLE notification_records (
    id                    UUID PRIMARY KEY,
    event_id              UUID         NOT NULL,
    transfer_ref          UUID         NOT NULL,
    recipient_customer_id UUID         NOT NULL,
    channel               VARCHAR(10)  NOT NULL DEFAULT 'EMAIL',
    template              VARCHAR(40)  NOT NULL,
    subject               VARCHAR(120) NOT NULL,
    body                  VARCHAR(500) NOT NULL,
    status                VARCHAR(10)  NOT NULL CHECK (status IN ('SENT')),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Second line of defence behind processed_events: one notification per event per recipient per channel.
    CONSTRAINT uq_notification_once UNIQUE (event_id, recipient_customer_id, channel)
);
CREATE INDEX idx_notifications_recipient ON notification_records (recipient_customer_id, created_at DESC);
