-- Tenant-scoped schema on the shard. Composite foreign keys (tenant_id, id) make a cross-tenant
-- reference impossible even if the application forgets the filter (REQ-DATA-11).
-- plans is a platform catalog, not tenant data: it moves to the global database in P6.1 (REQ-DATA-05),
-- which is why subscriptions reference plan_id without a foreign key.

CREATE TABLE plans (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    price_eur  NUMERIC(10, 2) NOT NULL,
    max_users  INT NOT NULL,
    features   JSONB,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE subscriptions (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    plan_id    UUID NOT NULL,
    status     VARCHAR(20) NOT NULL,
    start_date TIMESTAMPTZ NOT NULL,
    end_date   TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id)
);

CREATE TABLE invoices (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    subscription_id UUID NOT NULL,
    amount_eur      NUMERIC(10, 2) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at         TIMESTAMPTZ,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subscription_id) REFERENCES subscriptions (tenant_id, id)
);

CREATE TABLE payments (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    invoice_id   UUID NOT NULL,
    status       VARCHAR(20) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoices (tenant_id, id)
);

CREATE INDEX idx_subscriptions_tenant ON subscriptions (tenant_id);
CREATE INDEX idx_invoices_tenant ON invoices (tenant_id);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscriptions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoices
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payments
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
