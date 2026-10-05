package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** No names/emails - only ids (REQ-EVT-02), consumed later by a support-notification listener. */
public record AdminDemotionEscalatedPayload(UUID requestId, UUID targetUserId, UUID requestedBy) implements EventPayload {
}
