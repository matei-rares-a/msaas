package com.saas.org.lunch;

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
import java.time.LocalDate;
import java.util.UUID;

/** The weekly round of a tenant (REQ-LUNCH-02). Timestamps come from the injected clock, not from Hibernate. */
@Entity
@Table(schema = "org", name = "lunch_rounds")
@Getter
@Setter
public class LunchRound extends TenantScopedEntity {

    @Id
    private UUID id;

    /** The Monday of the week, in the tenant's time zone; one round per week. */
    @Column(nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false)
    private Instant deadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LunchStatus status;

    /** Why a CANCELLED round was cancelled: TOO_FEW_PARTICIPANTS, BY_APPROVER, TENANT_RESTRICTED or MODULE_DISABLED. */
    private String cancelReason;

    private UUID cancelledBy;

    private Instant formedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
