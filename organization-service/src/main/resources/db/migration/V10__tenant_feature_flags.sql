-- Feature flags per tenant (REQ-PERS-07, REQ-ARCH-02): one more flexible JSON document in the same
-- per-tenant settings row as the theme. A flag is a key in the document, so a new flag is a new key,
-- not a migration. A flag that is absent counts as disabled.
ALTER TABLE tenant_settings ADD COLUMN feature_flags_json JSONB NOT NULL DEFAULT '{}'::jsonb;
