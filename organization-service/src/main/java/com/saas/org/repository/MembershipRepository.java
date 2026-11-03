package com.saas.org.repository;

import com.saas.org.entity.Membership;
import com.saas.shared.tenancy.TenantScopedRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MembershipRepository extends TenantScopedRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    List<Membership> findAllByRole(Membership.Role role);

    /** SELECT ... FOR UPDATE: serializes what one member's leave requests do to each other (balance, overlap). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.userId = :userId")
    Optional<Membership> findByUserIdForUpdate(@Param("userId") UUID userId);

    List<Membership> findAllByRoleInAndReadOnlyFalse(Collection<Membership.Role> roles);

    List<Membership> findAllByRoleIn(Collection<Membership.Role> roles);

    long countByRole(Membership.Role role);
}
