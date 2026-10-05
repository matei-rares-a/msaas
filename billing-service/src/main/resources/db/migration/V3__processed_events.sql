-- Idempotency store for Kafka/RabbitMQ consumers (REQ-EVT-05): the consumer inserts eventId before
-- doing any business work, in the same transaction; a redelivered event finds the row already there
-- (unique id) and the handler is skipped, so retries/duplicates are safe (at-least-once delivery).

CREATE TABLE processed_events (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);

ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON processed_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
