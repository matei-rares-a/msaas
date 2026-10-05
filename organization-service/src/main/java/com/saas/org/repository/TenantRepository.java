package com.saas.org.repository;

import com.saas.org.entity.Tenant;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.UUID;

public interface TenantRepository extends TenantScopedRepository<Tenant, UUID> {
}
