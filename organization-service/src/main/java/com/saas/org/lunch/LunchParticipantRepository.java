package com.saas.org.lunch;

import com.saas.shared.tenancy.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LunchParticipantRepository extends TenantScopedRepository<LunchParticipant, UUID> {

    List<LunchParticipant> findAllByRoundId(UUID roundId);

    List<LunchParticipant> findAllByRoundIdIn(Collection<UUID> roundIds);

    boolean existsByRoundIdAndUserId(UUID roundId, UUID userId);

    long countByRoundId(UUID roundId);

    List<LunchParticipant> findAllByRoundIdAndUserId(UUID roundId, UUID userId);

    @Query("select p.roundId, count(p) from LunchParticipant p where p.roundId in :roundIds group by p.roundId")
    List<Object[]> countByRound(@Param("roundIds") Collection<UUID> roundIds);

    @Query("select p.roundId from LunchParticipant p where p.userId = :userId and p.roundId in :roundIds")
    List<UUID> roundsOf(@Param("userId") UUID userId, @Param("roundIds") Collection<UUID> roundIds);

    void deleteAllByUserId(UUID userId);

    void deleteAllByRoundIdIn(Collection<UUID> roundIds);
}
