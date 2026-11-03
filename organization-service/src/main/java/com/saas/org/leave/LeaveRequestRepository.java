package com.saas.org.leave;

import com.saas.shared.tenancy.TenantScopedRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LeaveRequestRepository extends TenantScopedRepository<LeaveRequest, UUID>, JpaSpecificationExecutor<LeaveRequest> {

    /** Requests of one member in the given states that share at least one day with [start, end]. */
    @Query("select count(r) from LeaveRequest r where r.userId = :userId and r.status in :statuses"
            + " and r.startDate <= :end and r.endDate >= :start")
    long countOverlapping(@Param("userId") UUID userId, @Param("statuses") Collection<LeaveStatus> statuses,
            @Param("start") LocalDate start, @Param("end") LocalDate end);

    /** Rest-leave days of a member in a calendar year that are in the given states (REQ-LEAVE-03). */
    @Query("select coalesce(sum(r.days), 0) from LeaveRequest r where r.userId = :userId and r.type = :type"
            + " and r.status = :status and r.startDate >= :yearStart and r.startDate <= :yearEnd")
    long sumDays(@Param("userId") UUID userId, @Param("type") LeaveType type, @Param("status") LeaveStatus status,
            @Param("yearStart") LocalDate yearStart, @Param("yearEnd") LocalDate yearEnd);

    /** Approved leave that shares at least one day with [from, to], earliest first (the team calendar). */
    @Query("select r from LeaveRequest r where r.status = com.saas.org.leave.LeaveStatus.APPROVED"
            + " and r.startDate <= :to and r.endDate >= :from order by r.startDate, r.id")
    List<LeaveRequest> findApprovedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    void deleteAllByUserId(UUID userId);
}
