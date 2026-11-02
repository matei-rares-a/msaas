package com.saas.org.repository;

import com.saas.org.entity.TenantSettings;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantSettingsRepository extends TenantScopedRepository<TenantSettings, UUID> {

    /** The tenant filter is applied automatically, so this is the current tenant's single row, if any. */
    Optional<TenantSettings> findFirstByOrderByCreatedAtAsc();
}
