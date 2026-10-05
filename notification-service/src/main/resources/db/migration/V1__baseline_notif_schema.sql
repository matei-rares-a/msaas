-- Tenant-scoped schema on the shard. Email logs keep metadata only (REQ-NOTIF-06); they are folded
-- into the tenant activity log in P9.4. Identity emails (password reset) are never stored here.

CREATE TABLE email_logs (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    to_email   VARCHAR(255) NOT NULL,
    subject    VARCHAR(255),
    status     VARCHAR(20) NOT NULL,
    event_type VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE in_app_notifications (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    user_id    UUID NOT NULL,
    title      VARCHAR(255) NOT NULL,
    message    VARCHAR(2000) NOT NULL,
    level      VARCHAR(20) NOT NULL,
    is_read    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_in_app_tenant_user ON in_app_notifications (tenant_id, user_id);

ALTER TABLE email_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_logs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE in_app_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE in_app_notifications FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON in_app_notifications
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
