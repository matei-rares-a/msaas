package com.saas.org.leave;

import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeaveBalanceOverrideRepository extends TenantScopedRepository<LeaveBalanceOverride, UUID> {

    Optional<LeaveBalanceOverride> findByUserId(UUID userId);

    void deleteAllByUserId(UUID userId);
}
