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

import java.time.Instant;
import java.util.UUID;

/** A demotion that would leave a tenant with a single ADMIN, pending the target's acceptance (REQ-ROLE-04). */
@Entity
@Table(schema = "org", name = "admin_demotion_requests")
@Getter
@Setter
public class AdminDemotionRequest extends TenantScopedEntity {

    public enum Status {PENDING, ACCEPTED, REJECTED, ESCALATED}

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID targetUserId;

    @Column(nullable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Membership.Role newRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Set from an injected {@code Clock}, not {@code @CreationTimestamp}: tests move it with MutableClock. */
    @Column(nullable = false)
    private Instant createdAt;

    private Instant respondedAt;
}
