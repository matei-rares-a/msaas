-- Business modules and the leave-request module (REQ-MOD-01, REQ-LEAVE-01..08).
--
-- Settings that belong to the organization as a whole go on the existing one-row-per-tenant document:
--   modules_json            which modules the ADMIN switched on, {"LEAVE": true}; a missing module is off
--   timezone                the tenant's time zone, used for calendar dates and cut-off times
--   leave_default_allowance yearly rest-leave days given to every member unless overridden
-- A tenant that has no settings row yet gets these defaults when the row is first created.
ALTER TABLE tenant_settings ADD COLUMN modules_json JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE tenant_settings ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Europe/Bucharest';
ALTER TABLE tenant_settings ADD COLUMN leave_default_allowance INT NOT NULL DEFAULT 21
    CHECK (leave_default_allowance BETWEEN 0 AND 366);

-- A member's request for leave. days is the number of working days in the interval, counted when the request is
-- made (a holiday added later does not change requests already made). The foreign key to memberships has no
-- cascade on purpose: removing a member must delete their requests explicitly (offboarding does), and the
-- database refuses to leave one behind.
CREATE TABLE leave_requests (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    user_id          UUID NOT NULL,
    leave_type       VARCHAR(20) NOT NULL CHECK (leave_type IN ('ODIHNA', 'FARA_PLATA')),
    start_date       DATE NOT NULL,
    end_date         DATE NOT NULL,
    days             INT NOT NULL CHECK (days >= 1),
    reason           VARCHAR(500),
    status           VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    auto_approved    BOOLEAN NOT NULL DEFAULT FALSE,
    decided_by       UUID,
    decision_comment VARCHAR(500),
    decided_at       TIMESTAMPTZ,
    cancelled_by     UUID,
    cancelled_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CHECK (end_date >= start_date),
    CHECK (EXTRACT(YEAR FROM start_date) = EXTRACT(YEAR FROM end_date)),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id)
);

CREATE INDEX idx_leave_requests_user ON leave_requests (tenant_id, user_id, start_date);
CREATE INDEX idx_leave_requests_status ON leave_requests (tenant_id, status, start_date);

-- A per-member yearly allowance that replaces the tenant default.
CREATE TABLE leave_balance_overrides (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    user_id    UUID NOT NULL,
    allowance  INT NOT NULL CHECK (allowance BETWEEN 0 AND 366),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id)
);

-- Days the tenant does not count as working days, besides Saturday and Sunday.
CREATE TABLE public_holidays (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    holiday_date DATE NOT NULL,
    name         VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, holiday_date),
    FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE CASCADE
);

ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_requests
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE leave_balance_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_balance_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_balance_overrides
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE public_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE public_holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public_holidays
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);
