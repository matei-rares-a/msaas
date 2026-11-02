package com.saas.org.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "org", name = "invitations")
@Getter
@Setter
public class Invitation extends TenantScopedEntity {

    public enum Status {PENDING, ACCEPTED, REVOKED}

    @Id
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Membership.Role role;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** The user who accepted this invitation; lets offboarding anonymize {@link #email} by id (REQ-DATA-01). */
    private UUID acceptedBy;

    @CreationTimestamp
    private Instant createdAt;
}
