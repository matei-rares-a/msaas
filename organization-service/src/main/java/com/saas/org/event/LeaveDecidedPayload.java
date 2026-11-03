package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/**
 * What happened to a leave request, for {@code LEAVE_APPROVED}, {@code LEAVE_REJECTED}, {@code LEAVE_AUTO_APPROVED} and
 * {@code LEAVE_CANCELLED} (REQ-LEAVE-07, REQ-MON-01). {@code actorUserId} is who did it (the requester themselves for an
 * auto-approval); {@code previousStatus} is the status before. Never the reason or the decision comment: those are
 * free text and stay in this service. Dates are ISO strings.
 */
public record LeaveDecidedPayload(UUID tenantId, UUID requestId, UUID actorUserId, UUID requesterUserId, String leaveType,
        String startDate, String endDate, int days, String previousStatus) implements EventPayload {
}
