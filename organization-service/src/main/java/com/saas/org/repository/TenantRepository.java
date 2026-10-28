package com.saas.org.repository;

import com.saas.org.entity.Tenant;
import com.saas.shared.tenancy.TenantScopedRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends TenantScopedRepository<Tenant, UUID> {

    // Closes the count-then-insert race on maxUsers enforcement (design-review.json finding 3):
    // serializes concurrent createInvitation calls for the same tenant.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> findByIdForUpdate(@Param("id") UUID id);
}
