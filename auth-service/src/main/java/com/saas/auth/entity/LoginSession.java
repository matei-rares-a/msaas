package com.saas.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Opaque single-use session used only for the multi-tenant login step (REQ-AUTH-02). */
@Entity
@Table(schema = "auth", name = "login_sessions")
@Getter
@Setter
public class LoginSession {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean used;

    @Column(nullable = false)
    private Instant createdAt;
}
