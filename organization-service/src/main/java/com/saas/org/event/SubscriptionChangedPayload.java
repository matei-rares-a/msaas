package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/**
 * Mirrors billing-service's own {@code SubscriptionChangedPayload} field-for-field (by wire contract,
 * not by Maven dependency - organization-service may not depend on billing-service, REQ-DATA-10).
 * Used only to deserialize an incoming envelope; never published by this service.
 */
public record SubscriptionChangedPayload(UUID tenantId, UUID planId, int maxUsers, String status, UUID actorUserId,
        String previousStatus) implements EventPayload {
}
