package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** A role was changed right away (REQ-MON-01); ids and role names only (REQ-EVT-02). */
public record MemberRoleChangedPayload(UUID tenantId, UUID actorUserId, UUID targetUserId, String previousRole, String newRole)
        implements EventPayload {
}
