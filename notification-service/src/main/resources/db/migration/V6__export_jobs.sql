-- Asynchronous activity-journal exports (REQ-MON-05, REQ-SCALE-03). A request whose estimated duration is
-- over the threshold becomes a row here (PENDING) plus an outbox command; the worker fills `content` and
-- sets READY. The file is a snapshot of the journal at the time the worker ran and lives only until
-- `expires_at`. Tenant-scoped like every other table: tenant_id, composite key, forced RLS.

CREATE TABLE export_jobs (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    requested_by UUID NOT NULL,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'READY')),
    filter       JSONB NOT NULL,
    row_count    BIGINT,
    content      BYTEA,
    created_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_export_jobs_tenant_user ON export_jobs (tenant_id, requested_by);

ALTER TABLE export_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON export_jobs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
