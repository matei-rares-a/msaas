package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** No email/name - only ids (REQ-EVT-02); consumed later by journal/notification listeners (P9.1/P9.5). */
public record InvitationAcceptedPayload(UUID invitationId, UUID tenantId, UUID membershipId, UUID userId, String role)
        implements EventPayload {
}
