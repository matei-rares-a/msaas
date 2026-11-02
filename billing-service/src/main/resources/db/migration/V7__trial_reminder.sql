-- A trial gets one "expiring soon" notice (REQ-NOTIF-03). The job that sends it reads across tenants with a
-- SELECT-only role, so it cannot record that it sent the notice; the tenant-scoped write that publishes the
-- event sets this column in the same transaction, which is what makes the notice happen exactly once.
ALTER TABLE subscriptions ADD COLUMN trial_reminder_sent_at TIMESTAMPTZ;
