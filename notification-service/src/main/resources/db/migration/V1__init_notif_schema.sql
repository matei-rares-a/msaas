-- Notification Service Schema

CREATE SCHEMA IF NOT EXISTS notif;

SET search_path TO notif;

CREATE TABLE IF NOT EXISTS email_logs (
    id          UUID PRIMARY KEY,
    to_email    VARCHAR(255) NOT NULL,
    subject     VARCHAR(255),
    body        TEXT,
    status      VARCHAR(50) DEFAULT 'SENT',
    event_type  VARCHAR(100),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_email_logs_to_email ON email_logs(to_email);
CREATE INDEX IF NOT EXISTS idx_email_logs_status ON email_logs(status);
