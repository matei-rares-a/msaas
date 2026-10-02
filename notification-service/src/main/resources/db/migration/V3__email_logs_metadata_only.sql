-- Email logs keep metadata only (REQ-NOTIF-06); the address search endpoint is gone (REQ-NOTIF-07).

ALTER TABLE notif.email_logs DROP COLUMN IF EXISTS body;
DROP INDEX IF EXISTS notif.idx_email_logs_to_email;
