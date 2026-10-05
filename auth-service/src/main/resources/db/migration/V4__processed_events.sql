-- Idempotency store for identity-level consumers (no tenant): same role as the tenant-scoped version
-- in org/billing/notif, but global and without RLS, like the rest of auth. REQ-EVT-05.

CREATE TABLE auth.processed_events (
    id           UUID PRIMARY KEY,
    tenant_id    UUID,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
