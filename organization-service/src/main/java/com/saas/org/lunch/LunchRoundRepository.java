package com.saas.org.lunch;

import com.saas.shared.tenancy.TenantScopedRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LunchRoundRepository extends TenantScopedRepository<LunchRound, UUID> {

    /** SELECT ... FOR UPDATE: what enrolling, withdrawing, cancelling and forming do to one round happens one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LunchRound r where r.weekStart = :weekStart")
    Optional<LunchRound> findByWeekStartForUpdate(@Param("weekStart") LocalDate weekStart);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LunchRound r where r.id = :id")
    Optional<LunchRound> findByIdForUpdate(@Param("id") UUID id);

    Optional<LunchRound> findByWeekStart(LocalDate weekStart);

    List<LunchRound> findAllByStatus(LunchStatus status);

    List<LunchRound> findAllByStatus(LunchStatus status, Pageable pageable);

    List<LunchRound> findAllByDeadlineAtBefore(Instant cutoff);

    /**
     * Creates the round of a week unless it exists; two members enrolling at the same moment both succeed and share it. The
     * tenant is given explicitly because a native statement does not get Hibernate's tenant discriminator.
     */
    @Modifying
    @Query(value = "insert into org.lunch_rounds (id, tenant_id, week_start, deadline_at, status, created_at, updated_at)"
            + " values (:id, :tenantId, :weekStart, :deadlineAt, 'OPEN', :now, :now)"
            + " on conflict (tenant_id, week_start) do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("weekStart") LocalDate weekStart,
            @Param("deadlineAt") Instant deadlineAt, @Param("now") Instant now);
}
