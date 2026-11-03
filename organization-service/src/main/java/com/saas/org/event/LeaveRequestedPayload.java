package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.List;
import java.util.UUID;

/**
 * A new leave request is waiting for a decision (REQ-LEAVE-07). {@code approverUserIds} are the other members who can
 * decide it - MANAGER or ADMIN without the read-only flag, not the requester - so the notification service need not
 * ask who they are. No type, no reason, no name: identifiers and the dates only (REQ-EVT-02).
 */
public record LeaveRequestedPayload(UUID tenantId, UUID requestId, UUID requesterUserId, List<UUID> approverUserIds,
        String startDate, String endDate) implements EventPayload {
}
