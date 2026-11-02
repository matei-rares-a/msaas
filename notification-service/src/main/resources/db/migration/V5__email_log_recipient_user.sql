-- Offboarding (REQ-DATA-01): the recipient's address in an email log is personal data. Linking the log
-- to the member it was sent to lets offboarding anonymize it by id. Nullable: invitations go to people
-- who are not members yet, and identity emails (password reset) are never logged here at all.
ALTER TABLE email_logs ADD COLUMN recipient_user_id UUID;

CREATE INDEX idx_email_logs_recipient ON email_logs (tenant_id, recipient_user_id) WHERE recipient_user_id IS NOT NULL;
