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
@Table(schema = "org", name = "memberships")
@Getter
@Setter
public class Membership extends TenantScopedEntity {

    public enum Role {ADMIN, MANAGER, USER}

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Write access, independent of the role (REQ-ROLE-06); enforced in P4.1. */
    @Column(nullable = false)
    private boolean readOnly;

    @CreationTimestamp
    private Instant createdAt;
}
