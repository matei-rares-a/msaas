package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/**
 * The leave policy was changed by an ADMIN (REQ-LEAVE-07, REQ-MON-01). {@code change} says which part:
 * {@code DEFAULT_ALLOWANCE}, {@code BALANCE_OVERRIDE}, {@code BALANCE_OVERRIDE_REMOVED}, {@code HOLIDAY_ADDED} or
 * {@code HOLIDAY_REMOVED}. The other fields are set only when they apply; a date is an ISO string so the event reads
 * the same whatever mapper wrote it.
 */
public record LeavePolicyChangedPayload(UUID tenantId, UUID actorUserId, String change, UUID targetUserId,
        Integer allowance, String holidayDate) implements EventPayload {
}
