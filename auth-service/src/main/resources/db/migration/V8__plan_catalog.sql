-- Global, non-sharded platform catalog (REQ-DATA-05, DATA-12): plans are never deleted, only
-- deactivated, so existing subscriptions (which reference plan_id with no foreign key, REQ-DATA-10)
-- never end up pointing at a row that disappeared. Moved here from billing.plans (P2.3 interim).
CREATE TABLE global.plans (
    id         UUID PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    price_eur  NUMERIC(10, 2) NOT NULL,
    max_users  INT NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE FUNCTION global.reject_plan_deletion() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'plans are never deleted, only deactivated (REQ-DATA-12)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER no_plan_deletion
    BEFORE DELETE ON global.plans
    FOR EACH ROW EXECUTE FUNCTION global.reject_plan_deletion();
