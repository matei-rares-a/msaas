-- V6 granted org_auth_reader (BYPASSRLS) SELECT on the whole invitations table, which let that role read
-- every tenant's invitee emails. It only needs to resolve a token to its tenant before the tenant
-- context exists (InvitationTokenLookup: SELECT tenant_id FROM invitations WHERE token = ?), so it is
-- narrowed to exactly those two columns. Guarded like V4/V6 for lightweight test containers.

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'org_auth_reader') THEN
        REVOKE SELECT ON invitations FROM org_auth_reader;
        GRANT SELECT (tenant_id, token) ON invitations TO org_auth_reader;
    END IF;
END $$;
