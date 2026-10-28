package com.saas.org.repository;

import com.saas.org.entity.Invitation;
import com.saas.shared.tenancy.TenantScopedRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends TenantScopedRepository<Invitation, UUID> {

    Optional<Invitation> findByToken(String token);

    long countByStatusAndExpiresAtAfter(Invitation.Status status, Instant notAfter);

    List<Invitation> findAllByStatusOrderByCreatedAtDesc(Invitation.Status status);
}
