-- Opaque, single-use login session used only when a user belongs to more than one tenant (REQ-AUTH-02):
-- /auth/login returns this id instead of a token; /auth/login/tenant exchanges it (+ a chosen tenantId)
-- for the real token, after re-checking membership so a user removed from a tenant meanwhile gets
-- nothing for it (REQ-EVT-09). Global identity data, no tenant_id, no RLS - same as the rest of auth.

CREATE TABLE login_sessions (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL
);
