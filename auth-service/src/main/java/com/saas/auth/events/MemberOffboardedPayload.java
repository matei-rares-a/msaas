package com.saas.auth.events;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** Mirrors organization-service's {@code MemberOffboardedPayload} by wire contract, not by Maven dependency (REQ-DATA-10). */
public record MemberOffboardedPayload(UUID tenantId, UUID userId) implements EventPayload {
}
