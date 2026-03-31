-- In-app notification support

SET search_path TO notif;

CREATE TABLE IF NOT EXISTS in_app_notifications (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    user_id         UUID NOT NULL,
    title           VARCHAR(255) NOT NULL,
    message         VARCHAR(2000) NOT NULL,
    level           VARCHAR(50) NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_in_app_org_user
    ON in_app_notifications(organization_id, user_id);
