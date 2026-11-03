package com.saas.org.leave;

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

/** One member's request for leave (REQ-LEAVE-01). Timestamps come from the injected clock, not from Hibernate. */
@Entity
@Table(schema = "org", name = "leave_requests")
@Getter
@Setter
public class LeaveRequest extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false)
    private LeaveType type;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** Working days in the interval, counted when the request was made. */
    @Column(nullable = false)
    private int days;

    /** Free text for the approvers; never leaves this service in an event or a notification. */
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status;

    @Column(nullable = false)
    private boolean autoApproved;

    private UUID decidedBy;

    private String decisionComment;

    private Instant decidedAt;

    private UUID cancelledBy;

    private Instant cancelledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
