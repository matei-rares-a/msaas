-- Offboarding (REQ-DATA-01): the invitee's email is tenant-scoped personal data. Linking an accepted
-- invitation to the user who accepted it lets offboarding anonymize that email by id, without the
-- service ever needing the user's address (the global identity lives in auth-service).
ALTER TABLE invitations ADD COLUMN accepted_by UUID;

CREATE INDEX idx_invitations_accepted_by ON invitations (tenant_id, accepted_by) WHERE accepted_by IS NOT NULL;
