package com.saas.auth.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** A user got a per-tenant token by logging in (REQ-MON-01); ids and the role name only, no email (REQ-EVT-02). */
public record LoginPayload(UUID tenantId, UUID userId, String role) implements EventPayload {
}
