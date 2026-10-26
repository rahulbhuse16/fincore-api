CREATE TABLE customers (
    id               UUID PRIMARY KEY,                 -- equals the customer_id claim issued by identity-service
    full_name        VARCHAR(120) NOT NULL,
    email            VARCHAR(254) NOT NULL UNIQUE,
    phone            VARCHAR(20),
    date_of_birth    DATE         NOT NULL,
    document_type    VARCHAR(20)  NOT NULL,
    document_last4   VARCHAR(4)   NOT NULL,            -- the full document number is never stored
    kyc_status       VARCHAR(10)  NOT NULL CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED')),
    kyc_reason       VARCHAR(80),
    kyc_updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status           VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED')),
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_customers_kyc ON customers (kyc_status);
