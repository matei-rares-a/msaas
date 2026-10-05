package com.saas.billing.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "billing", name = "subscriptions")
@Getter
@Setter
public class Subscription extends TenantScopedEntity {

    public enum Status {TRIALING, ACTIVE, PAST_DUE, SUSPENDED, CANCELED}

    @Id
    private UUID id;

    /** Plain id: the plan catalog lives in another database, so no foreign key (REQ-DATA-10). */
    @Column(nullable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant startDate;

    private Instant endDate;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
