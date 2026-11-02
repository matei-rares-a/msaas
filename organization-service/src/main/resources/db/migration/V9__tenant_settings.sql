-- Per-tenant settings (REQ-PERS-04): a flexible JSON document per tenant, so a new customisation
-- option is a new key, not a new migration. Today it holds the theme ({"paletteId": ..., "logoUrl": ...});
-- feature flags (P8.3) and later options follow the same pattern. One row per tenant.
CREATE TABLE tenant_settings (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL UNIQUE,
    theme_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE CASCADE
);

ALTER TABLE tenant_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
