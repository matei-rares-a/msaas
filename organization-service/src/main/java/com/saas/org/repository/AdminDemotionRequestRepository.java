package com.saas.org.repository;

import com.saas.org.entity.AdminDemotionRequest;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.UUID;

public interface AdminDemotionRequestRepository extends TenantScopedRepository<AdminDemotionRequest, UUID> {
}
