-- In-app notifications (REQ-NOTIF-01, REQ-NOTIF-05).
--
-- event_type says which event produced a notification, so a client can pick an icon or a link for it.
--
-- Retention: notifications older than the retention period (90 days) are deleted by a scheduled job that
-- sweeps every tenant. Under FORCE ROW LEVEL SECURITY a connection with no tenant sees nothing, so the job
-- cannot read or delete across tenants without help. The two policies below give it exactly that and no
-- more: while the job's transaction has set app.purge_before, rows created before that moment are visible
-- to it and deletable. Without the setting (every normal request) they match nothing, so tenant isolation is
-- unchanged, and there is no role that bypasses RLS.

ALTER TABLE in_app_notifications ADD COLUMN event_type VARCHAR(100);

CREATE INDEX idx_in_app_user_created ON in_app_notifications (tenant_id, user_id, created_at DESC);

CREATE POLICY retention_purge_select ON in_app_notifications FOR SELECT
    USING (created_at < NULLIF(current_setting('app.purge_before', TRUE), '')::timestamptz);
CREATE POLICY retention_purge_delete ON in_app_notifications FOR DELETE
    USING (created_at < NULLIF(current_setting('app.purge_before', TRUE), '')::timestamptz);
