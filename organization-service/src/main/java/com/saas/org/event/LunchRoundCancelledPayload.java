package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.List;
import java.util.UUID;

/**
 * A lunch round with participants was cancelled (REQ-LUNCH-03, REQ-NOTIF-03), by an approver, for too few participants, or
 * because the organization is in its billing grace period or suspended, or the module was switched off. The participants
 * to tell are in the event, so the notification service does not have to ask. Ids only.
 */
public record LunchRoundCancelledPayload(UUID tenantId, UUID roundId, String weekStart, String reason,
        List<UUID> participantUserIds) implements EventPayload {
}
