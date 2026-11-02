package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** The targeted ADMIN accepted or rejected their demotion (REQ-ROLE-04, REQ-MON-01); the actor is that ADMIN. */
public record AdminDemotionDecidedPayload(UUID tenantId, UUID requestId, UUID actorUserId, String newRole) implements EventPayload {
}
