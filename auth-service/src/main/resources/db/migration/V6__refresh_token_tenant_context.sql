-- Refresh tokens must carry the tenant context their access token was issued for (REQ-AUTH-04), so
-- rotation (P5.3) reissues the same per-tenant token instead of a generic one.
ALTER TABLE refresh_tokens
    ADD COLUMN tenant_id   UUID NOT NULL,
    ADD COLUMN role        VARCHAR(50) NOT NULL,
    ADD COLUMN read_only   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN tenant_name VARCHAR(255) NOT NULL;
