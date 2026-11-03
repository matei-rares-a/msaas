package com.saas.org.leave;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A leave request as the caller may see it: the requester sees their own, MANAGER and ADMIN see everyone's with the type
 * and the reason (REQ-LEAVE-06). The team calendar is a different, narrower view ({@link CalendarEntry}).
 */
public record LeaveRequestView(UUID id, UUID userId, LeaveType type, LocalDate startDate, LocalDate endDate, int days,
        String reason, LeaveStatus status, boolean autoApproved, UUID decidedBy, String decisionComment, Instant decidedAt,
        UUID cancelledBy, Instant cancelledAt, Instant createdAt) {

    static LeaveRequestView of(LeaveRequest request) {
        return new LeaveRequestView(request.getId(), request.getUserId(), request.getType(), request.getStartDate(),
                request.getEndDate(), request.getDays(), request.getReason(), request.getStatus(), request.isAutoApproved(),
                request.getDecidedBy(), request.getDecisionComment(), request.getDecidedAt(), request.getCancelledBy(),
                request.getCancelledAt(), request.getCreatedAt());
    }
}
