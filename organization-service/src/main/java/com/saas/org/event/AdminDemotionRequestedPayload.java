package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** A demotion that needs the target's acceptance was requested (REQ-ROLE-04, REQ-MON-01); ids and role names only. */
public record AdminDemotionRequestedPayload(UUID tenantId, UUID requestId, UUID actorUserId, UUID targetUserId, String newRole)
        implements EventPayload {
}
