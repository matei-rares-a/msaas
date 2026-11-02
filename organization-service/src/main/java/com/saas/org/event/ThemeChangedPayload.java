package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** The theme changed (REQ-MON-01): a new palette id, or - when {@code paletteId} is null - a new logo. */
public record ThemeChangedPayload(UUID tenantId, UUID actorUserId, String paletteId) implements EventPayload {
}
