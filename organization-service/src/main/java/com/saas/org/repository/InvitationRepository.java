package com.saas.org.repository;

import com.saas.org.entity.Invitation;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends TenantScopedRepository<Invitation, UUID> {

    Optional<Invitation> findByToken(String token);
}
