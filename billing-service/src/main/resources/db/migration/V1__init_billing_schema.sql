-- Billing Service Schema

CREATE SCHEMA IF NOT EXISTS billing;

SET search_path TO billing;

CREATE TABLE IF NOT EXISTS plans (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    price       DECIMAL(10, 2) NOT NULL,
    max_users   INT NOT NULL,
    features    JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS subscriptions (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    plan_id         UUID NOT NULL REFERENCES plans(id),
    status          VARCHAR(50) NOT NULL,
    start_date      TIMESTAMP NOT NULL,
    end_date        TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS invoices (
    id              UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    amount          DECIMAL(10, 2) NOT NULL,
    status          VARCHAR(50) NOT NULL,
    issued_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    paid_at         TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payments (
    id          UUID PRIMARY KEY,
    invoice_id  UUID NOT NULL REFERENCES invoices(id),
    status      VARCHAR(50) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_org_id ON subscriptions(organization_id);
CREATE INDEX IF NOT EXISTS idx_invoices_org_id ON invoices(organization_id);
CREATE INDEX IF NOT EXISTS idx_payments_invoice_id ON payments(invoice_id);
