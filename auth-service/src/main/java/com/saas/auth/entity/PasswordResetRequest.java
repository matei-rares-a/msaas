package com.saas.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** One password-reset request attempt, recorded regardless of outcome, for the hourly rate limit
 * (REQ-PWD-05). Global identity data, no tenant_id, no RLS - same as the rest of auth. */
@Entity
@Table(schema = "auth", name = "password_reset_requests")
@Data
@NoArgsConstructor
public class PasswordResetRequest {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String ipAddress;

    @Column(nullable = false)
    private Instant requestedAt;
}
