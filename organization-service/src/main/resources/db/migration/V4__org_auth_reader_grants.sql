-- Narrow, BYPASSRLS reader for the internal "memberships across all tenants for one user" lookup used
-- by auth at login (REQ-EVT-09: synchronous authorization, no cache). SELECT only, memberships only -
-- this role must never be able to write or to read tenants/invitations. Guarded like outbox_relay's
-- grants in P3.2: lightweight test containers without the full topology script have no such role.

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'org_auth_reader') THEN
        GRANT USAGE ON SCHEMA org TO org_auth_reader;
        GRANT SELECT ON org.memberships TO org_auth_reader;
    END IF;
END $$;
