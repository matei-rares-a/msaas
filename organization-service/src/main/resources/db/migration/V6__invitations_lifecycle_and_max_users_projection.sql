-- P7.1: invitation lifecycle (status supersedes the baseline boolean "accepted" - REQ-INV-01, INV-02)
-- and the local, eventually-consistent maxUsers projection fed by billing's SUBSCRIPTION_CHANGED
-- (REQ-BILL-03). Structure-only migration (no data to preserve pre-release, same as every other
-- migration in this project - AGENT_CONTEXT section 4).

ALTER TABLE invitations DROP COLUMN accepted;
ALTER TABLE invitations ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED'));
-- EXPIRED is never stored: expiry is derived at read time from expires_at vs. the injected Clock, so
-- there is nothing to reconcile/schedule (same reasoning as AdminDemotionRequest's PENDING/ACCEPTED/
-- REJECTED/ESCALATED, except "expired" has no actor and would just be a second source of truth).

-- Per-tenant invitation expiry override (REQ-INV-01); NULL means "use the platform default"
-- (saas.invitation.default-expiry-days, 7 days). tenant_settings (the flexible JSON store from
-- REQUIREMENTS.md) does not exist yet - it only arrives in P8.1 - so this is a plain column for now;
-- folding it into tenant_settings later is an additive change, not a breaking one.
ALTER TABLE tenants ADD COLUMN invitation_expiry_days INTEGER;

-- Local, eventually-consistent projection of the current plan's maxUsers (REQ-BILL-03), updated only
-- by SubscriptionChangedListener. NULL = no SUBSCRIPTION_CHANGED event ever processed for this tenant
-- yet -> invitations are rejected (fail-closed, design.md section 1), never silently unlimited.
ALTER TABLE tenants ADD COLUMN max_users INTEGER;

-- SECURITY NOTE (design-review.json finding 1, HIGH, left unresolved deliberately per this task's
-- explicit instruction not to renegotiate the Flyway migration structure): this grant widens
-- org_auth_reader to SELECT on invitations, which directly contradicts V4__org_auth_reader_grants.sql's
-- own comment ("this role must never be able to write or to read tenants/invitations"). Kept as-is;
-- flagged here so it stays visible for a reviewer/maintainer, to be redone with a dedicated
-- org_invitation_reader role if the team decides to close this finding later. Guarded like V4/V5 for
-- lightweight test containers that skip the full topology script.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'org_auth_reader') THEN
        GRANT SELECT ON invitations TO org_auth_reader;
    END IF;
END $$;
