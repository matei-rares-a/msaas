-- Tracks when a subscription first went PAST_DUE (REQ-BILL-04), so the 7-day grace period is
-- measured from the first failure, not reset by each retry attempt.
ALTER TABLE subscriptions ADD COLUMN past_due_since TIMESTAMPTZ;
