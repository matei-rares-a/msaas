-- The plan catalog moves to the global database (REQ-DATA-05, P6.1); it never lived in business
-- logic here, so dropping it is safe. subscriptions.plan_id keeps referencing it by id only, with no
-- foreign key (REQ-DATA-10), exactly as before.
DROP TABLE plans;
