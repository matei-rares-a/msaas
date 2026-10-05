-- Global catalogs, read-only for shard services: tenant -> shard map (REQ-DATA-02, DATA-06).
-- Writes come from auth_svc only; shard services get SELECT via the default privilege set in
-- db/init/01-topology.sh. One shard today; adding a row here is the only change needed for a new one.

CREATE TABLE global.tenant_shards (
    tenant_id  UUID PRIMARY KEY,
    shard_id   VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
