package com.saas.org.repository;

import com.saas.org.entity.Membership;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends TenantScopedRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    List<Membership> findAllByRole(Membership.Role role);
}
