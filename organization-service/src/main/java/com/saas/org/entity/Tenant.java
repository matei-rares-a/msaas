package com.saas.org.entity;

import com.saas.shared.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** The organisation as the UI calls it; the tenant in the model. Its id equals its tenant_id. */
@Entity
@Table(schema = "org", name = "tenants")
@Getter
@Setter
public class Tenant extends TenantScopedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
