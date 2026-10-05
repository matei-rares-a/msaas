-- Protects against a demotion that would leave a single ADMIN (REQ-ROLE-04): the targeted admin must
-- accept, or the request escalates to support after the configured deadline. Demoting/removing the
-- very last ADMIN (would leave zero) is rejected outright in the service layer, before any request
-- is ever created (REQ-ROLE-03).

CREATE TABLE admin_demotion_requests (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    target_user_id UUID NOT NULL,
    requested_by   UUID NOT NULL,
    new_role       VARCHAR(20) NOT NULL CHECK (new_role IN ('MANAGER', 'USER')),
    status         VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'ESCALATED')),
    created_at     TIMESTAMPTZ NOT NULL,
    responded_at   TIMESTAMPTZ,
    UNIQUE (tenant_id, id)
);

CREATE INDEX idx_admin_demotion_requests_pending ON admin_demotion_requests (created_at) WHERE status = 'PENDING';

ALTER TABLE admin_demotion_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_demotion_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON admin_demotion_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

-- The escalation job (P4.3) scans for overdue requests across every tenant; same guarded pattern as
-- outbox_relay/org_auth_reader's other grants, for test containers without the full topology script.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'org_auth_reader') THEN
        GRANT SELECT ON admin_demotion_requests TO org_auth_reader;
    END IF;
END $$;
