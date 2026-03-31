package com.saas.org.repository;

import com.saas.org.entity.Membership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
    List<Membership> findAllByOrganizationId(UUID orgId);
    Optional<Membership> findByUserIdAndOrganizationId(UUID userId, UUID orgId);
    boolean existsByUserIdAndOrganizationId(UUID userId, UUID orgId);
}
