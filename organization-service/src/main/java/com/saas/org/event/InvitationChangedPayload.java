package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** An invitation was created or revoked (REQ-MON-01); never the invitee's email (REQ-EVT-02). */
public record InvitationChangedPayload(UUID tenantId, UUID invitationId, UUID actorUserId, String role) implements EventPayload {
}
