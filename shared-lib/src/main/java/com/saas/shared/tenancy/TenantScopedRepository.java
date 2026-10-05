package com.saas.shared.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for tenant-scoped entities. Filtering is applied by Hibernate for every query
 * (see {@link TenantScopedEntity}); the interface marks the intent and is enforced by ArchUnit (P2.5).
 */
@NoRepositoryBean
public interface TenantScopedRepository<T extends TenantScopedEntity, ID> extends JpaRepository<T, ID> {
}
