-- Tenant-scoped schema on the shard. Every table carries tenant_id, has RLS enabled AND forced
-- (so the owning role cannot bypass it) and a fail-closed policy: without app.tenant_id no row is
-- visible. Foreign keys inside the service are composite (tenant_id, id), so the database itself
-- rejects a reference to another tenant's row (REQ-DATA-02, 03, 07, 09, 10, 11).

CREATE TABLE tenants (
    id         UUID PRIMARY KEY,
    -- the tenant row belongs to itself; kept as a column so every table looks the same to RLS
    tenant_id  UUID NOT NULL UNIQUE CHECK (tenant_id = id),
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE memberships (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    user_id    UUID NOT NULL,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'USER')),
    read_only  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, user_id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE CASCADE
);

CREATE TABLE invitations (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    email      VARCHAR(255) NOT NULL,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MANAGER', 'USER')),
    token      VARCHAR(512) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE CASCADE
);

CREATE INDEX idx_memberships_user_id ON memberships (user_id);
CREATE INDEX idx_invitations_email ON invitations (tenant_id, email);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON memberships
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invitations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
