package com.saas.shared.events;

import java.util.UUID;

/**
 * Identity-level payloads (no tenant) must say which user they're about, so the relay can still
 * partition/order Kafka records per subject even without a tenantId (REQ-EVT-10).
 */
public interface IdentityEventPayload extends EventPayload {
    UUID subjectUserId();
}
