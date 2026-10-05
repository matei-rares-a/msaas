-- Outbox for domain events published from this service (REQ-EVT-01, REQ-EVT-04): a write to this table
-- happens in the same transaction as the business change it describes, so a rollback here means the
-- event never existed. The dedicated outbox_relay role (BYPASSRLS, see db/init/01-topology.sh) polls and
-- publishes rows, then marks them published; it never writes to any other table in this schema.

CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    destination  VARCHAR(20) NOT NULL CHECK (destination IN ('KAFKA', 'RABBITMQ')),
    channel      VARCHAR(255) NOT NULL,
    routing_key  VARCHAR(255) NOT NULL,
    payload      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON outbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'outbox_relay') THEN
        GRANT USAGE ON SCHEMA billing TO outbox_relay;
        GRANT SELECT, UPDATE ON billing.outbox TO outbox_relay;
    END IF;
END $$;
