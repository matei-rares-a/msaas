-- Lunch roulette (REQ-MOD-01, REQ-LUNCH-01..05).
--
-- The weekly settings go on the one-row-per-tenant settings document, next to the time zone and the leave allowance:
-- the weekday of the cut-off, the cut-off time in the tenant's time zone, and the size of a group (2 to 4).
ALTER TABLE tenant_settings ADD COLUMN lunch_day VARCHAR(10) NOT NULL DEFAULT 'THURSDAY'
    CHECK (lunch_day IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'));
ALTER TABLE tenant_settings ADD COLUMN lunch_cutoff TIME NOT NULL DEFAULT '11:00';
ALTER TABLE tenant_settings ADD COLUMN lunch_group_size INT NOT NULL DEFAULT 2 CHECK (lunch_group_size BETWEEN 2 AND 4);

-- One round per week (week_start is the Monday of the week in the tenant's time zone). deadline_at is the cut-off
-- worked out from the settings, kept on the row so the job that forms the groups can find the rounds that are due across
-- every tenant with one query. status: OPEN until the cut-off, then FORMED (groups made) or CANCELLED (and why).
CREATE TABLE lunch_rounds (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    week_start    DATE NOT NULL,
    deadline_at   TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'FORMED', 'CANCELLED')),
    cancel_reason VARCHAR(30),
    cancelled_by  UUID,
    formed_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, week_start),
    FOREIGN KEY (tenant_id) REFERENCES tenants (tenant_id) ON DELETE CASCADE
);

-- A member's enrolment in a round; group_number is empty until the groups are formed and is the group the member
-- landed in afterwards, so a group is simply the participants of one round that share a number. The reference to the
-- membership has no cascade on purpose: removing a member deletes their participations explicitly (offboarding), and the
-- database refuses to leave one behind.
CREATE TABLE lunch_participants (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL,
    round_id     UUID NOT NULL,
    user_id      UUID NOT NULL,
    group_number INT CHECK (group_number >= 1),
    created_at   TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, round_id, user_id),
    FOREIGN KEY (tenant_id, round_id) REFERENCES lunch_rounds (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id)
);

CREATE INDEX idx_lunch_rounds_open ON lunch_rounds (deadline_at) WHERE status = 'OPEN';
CREATE INDEX idx_lunch_rounds_deadline ON lunch_rounds (deadline_at);
CREATE INDEX idx_lunch_participants_user ON lunch_participants (tenant_id, user_id);

ALTER TABLE lunch_rounds ENABLE ROW LEVEL SECURITY;
ALTER TABLE lunch_rounds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lunch_rounds
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

ALTER TABLE lunch_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE lunch_participants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lunch_participants
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid);

-- The jobs that form due rounds and sweep old ones look across every tenant, but only at WHICH tenant has something to do
-- (ids and dates): the narrow BYPASSRLS reader of the other org jobs may read the rounds table and nothing of the
-- participants. The work itself then runs per tenant, under RLS. Guarded like the other grants for lightweight test
-- containers without the full topology script.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'org_auth_reader') THEN
        GRANT SELECT ON lunch_rounds TO org_auth_reader;
    END IF;
END $$;
