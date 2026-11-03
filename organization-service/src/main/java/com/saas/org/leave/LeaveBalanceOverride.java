package com.saas.org.leave;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A member's own yearly allowance, replacing the tenant default (REQ-LEAVE-03). */
@Entity
@Table(schema = "org", name = "leave_balance_overrides")
@Getter
@Setter
public class LeaveBalanceOverride extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int allowance;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
