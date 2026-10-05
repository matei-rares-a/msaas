package com.saas.shared.events;

/**
 * Kafka/RabbitMQ routing key for an event envelope: {@code tenantId} for tenant-scoped events, the
 * subject user for identity events, so records about the same tenant/user keep their order (REQ-EVT-10).
 */
public final class EventRoutingKey {

    private EventRoutingKey() {
    }

    public static String resolve(EventEnvelope<?> envelope) {
        if (envelope.isTenantScoped()) {
            return envelope.getTenantId().toString();
        }
        if (envelope.getPayload() instanceof IdentityEventPayload identityPayload) {
            return identityPayload.subjectUserId().toString();
        }
        throw new IllegalStateException("identity event '" + envelope.getEventType()
                + "' has no tenantId and its payload does not implement IdentityEventPayload,"
                + " so no routing key can be derived (REQ-EVT-10)");
    }
}
