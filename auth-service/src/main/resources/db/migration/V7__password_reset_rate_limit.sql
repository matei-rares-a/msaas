-- Records every password-reset request attempt (regardless of outcome), for the hourly rate limit
-- (REQ-PWD-05: 3/hour per email, 10/hour per IP). Global identity data, no tenant_id, no RLS.
CREATE TABLE password_reset_requests (
    id           UUID PRIMARY KEY,
    email        VARCHAR(255) NOT NULL,
    ip_address   VARCHAR(45) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_requests_email ON password_reset_requests (email, requested_at);
CREATE INDEX idx_password_reset_requests_ip ON password_reset_requests (ip_address, requested_at);
