package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** A business module was switched on or off by an ADMIN (REQ-MOD-01, REQ-MON-01); identifiers and flags only. */
public record ModuleChangedPayload(UUID tenantId, UUID actorUserId, String module, boolean enabled) implements EventPayload {
}
