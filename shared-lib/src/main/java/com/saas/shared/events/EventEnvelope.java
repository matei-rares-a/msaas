package com.saas.shared.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Common envelope for every event/command published to Kafka or RabbitMQ (REQ-EVT-06): eventId,
 * eventType, version, tenantId, occurredAt, payload. Only-add evolution: new fields are optional,
 * existing ones are never removed or repurposed (REQ-ARCH-03).
 *
 * <p>{@code tenantId} is required for tenant-scoped events and absent for identity events (user
 * registered, password changed) - use {@link #forTenant} / {@link #forIdentity}. The consumer sets
 * {@code TenantContext} from it before writing anything, so RLS applies (P3.3).
 */
@Getter
@EqualsAndHashCode
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EventEnvelope<T extends EventPayload> {

    private final UUID eventId;
    private final String eventType;
    private final int version;
    private final UUID tenantId;
    private final Instant occurredAt;
    private final T payload;

    @JsonCreator
    EventEnvelope(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("tenantId") UUID tenantId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("payload") T payload) {
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.version = version;
        this.tenantId = tenantId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    public static <T extends EventPayload> EventEnvelope<T> forTenant(String eventType, int version, UUID tenantId, T payload) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required for a tenant-scoped event (REQ-EVT-06): " + eventType);
        }
        return new EventEnvelope<>(UUID.randomUUID(), eventType, version, tenantId, Instant.now(), payload);
    }

    public static <T extends EventPayload> EventEnvelope<T> forIdentity(String eventType, int version, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, version, null, Instant.now(), payload);
    }

    public boolean isTenantScoped() {
        return tenantId != null;
    }
}
