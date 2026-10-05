-- Outbox for identity-level events (no tenant): auth is the global, non-sharded database, so this table
-- has no RLS, same as the rest of the auth schema; tenant_id stays nullable and is always null here
-- (kept only so shared-lib's OutboxWriter can use one INSERT shape across every service). REQ-EVT-01, REQ-EVT-04.

CREATE TABLE auth.outbox (
    id           UUID PRIMARY KEY,
    tenant_id    UUID,
    event_type   VARCHAR(255) NOT NULL,
    destination  VARCHAR(20) NOT NULL CHECK (destination IN ('KAFKA', 'RABBITMQ')),
    channel      VARCHAR(255) NOT NULL,
    routing_key  VARCHAR(255) NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON auth.outbox (created_at) WHERE published_at IS NULL;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'outbox_relay') THEN
        GRANT USAGE ON SCHEMA auth TO outbox_relay;
        GRANT SELECT, UPDATE ON auth.outbox TO outbox_relay;
    END IF;
END $$;
