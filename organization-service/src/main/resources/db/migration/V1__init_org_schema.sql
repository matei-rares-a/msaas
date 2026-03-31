-- Organization Service Schema

CREATE SCHEMA IF NOT EXISTS org;

SET search_path TO org;

CREATE TABLE IF NOT EXISTS organizations (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    owner_id    UUID NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS memberships (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role            VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, organization_id)
);

CREATE TABLE IF NOT EXISTS invitations (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role            VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    token           VARCHAR(512) NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    accepted        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_memberships_user_id ON memberships(user_id);
CREATE INDEX IF NOT EXISTS idx_memberships_org_id ON memberships(organization_id);
CREATE INDEX IF NOT EXISTS idx_invitations_token ON invitations(token);
CREATE INDEX IF NOT EXISTS idx_invitations_email ON invitations(email);
