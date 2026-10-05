-- Core of the activity journal (REQ-MON-06, REQ-MON-07): one generic, append-only entry per sensitive
-- action, partitioned by month on created_at. Partitions are created/dropped by the maintenance job in
-- ActivityLogPartitionMaintenance, not by migrations (a migration runs once; the current/next month keep
-- changing). RLS is set once on the partitioned parent; Postgres enforces it on every partition too.
--
-- The unique-key convention used by every other tenant table ((tenant_id, id), checked generically by
-- SchemaConventionsIT) does not apply here: a partitioned table's key must include the partition column
-- (created_at), and nothing ever references activity_log by foreign key, so (id, created_at) is enough.
-- Documented as an explicit exception in SchemaConventionsIT, same spirit as billing.plans in P2.3.

CREATE TABLE activity_log (
    id            UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    actor_user_id UUID,
    metadata      JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_activity_log_tenant_id ON activity_log (tenant_id);

ALTER TABLE activity_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON activity_log
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
