CREATE TABLE users (
    id              UUID PRIMARY KEY,
    username        VARCHAR(50)  NOT NULL UNIQUE,
    email           VARCHAR(254) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,              -- bcrypt; plaintext passwords are never stored or logged
    role            VARCHAR(10)  NOT NULL CHECK (role IN ('CUSTOMER','ADMIN')),
    customer_id     UUID UNIQUE,                         -- NULL for ADMIN
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
