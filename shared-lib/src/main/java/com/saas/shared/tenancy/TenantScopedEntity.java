package com.saas.shared.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * Base class for every tenant-scoped entity. {@code @TenantId} makes Hibernate fill the column on
 * insert and add {@code tenant_id = ?} to every query, so isolation does not depend on each query
 * remembering the filter; Postgres RLS is the second, independent layer.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }
}
