-- Narrow, BYPASSRLS reader for the trial-conversion job (P6.3): across every tenant, SELECT-only on
-- billing.subscriptions. Guarded like org_auth_reader's grants: lightweight test containers without
-- the full topology script have no such role.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billing_job_reader') THEN
        GRANT USAGE ON SCHEMA billing TO billing_job_reader;
        GRANT SELECT ON billing.subscriptions TO billing_job_reader;
    END IF;
END $$;
