package com.saas.shared.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private record SamplePayload(UUID subjectId, UUID actorId) implements EventPayload {
    }

    // consumers must tolerate fields added later, both on the envelope and inside the payload (REQ-ARCH-03)
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @Tag("REQ-EVT-06")
    void tenantEventRoundTripsThroughJson() throws Exception {
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload(UUID.randomUUID(), UUID.randomUUID());
        EventEnvelope<SamplePayload> original = EventEnvelope.forTenant("MEMBERSHIP_CHANGED", 1, tenantId, payload);

        String json = mapper.writeValueAsString(original);
        JavaType type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class);
        EventEnvelope<SamplePayload> restored = mapper.readValue(json, type);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isTenantScoped()).isTrue();
    }

    @Test
    @Tag("REQ-EVT-06")
    void identityEventHasNoTenantIdAndRoundTrips() throws Exception {
        EventEnvelope<SamplePayload> original = EventEnvelope.forIdentity(
                "USER_REGISTERED", 1, new SamplePayload(UUID.randomUUID(), UUID.randomUUID()));

        String json = mapper.writeValueAsString(original);
        JavaType type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class);
        EventEnvelope<SamplePayload> restored = mapper.readValue(json, type);

        assertThat(original.getTenantId()).isNull();
        assertThat(original.isTenantScoped()).isFalse();
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @Tag("REQ-EVT-06")
    void tenantEventRequiresATenantId() {
        assertThatThrownBy(() -> EventEnvelope.forTenant("MEMBERSHIP_CHANGED", 1, null,
                new SamplePayload(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    @Tag("REQ-ARCH-03")
    void onlyAddEvolutionIgnoresUnknownFieldsFromANewerProducer() throws Exception {
        String jsonFromTheFuture = """
                {"eventId":"%s","eventType":"MEMBERSHIP_CHANGED","version":2,"tenantId":"%s",
                 "occurredAt":"2026-01-01T00:00:00Z",
                 "payload":{"subjectId":"%s","actorId":"%s","somethingAddedLater":"ignored"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        JavaType type = mapper.getTypeFactory().constructParametricType(EventEnvelope.class, SamplePayload.class);
        EventEnvelope<SamplePayload> restored = mapper.readValue(jsonFromTheFuture, type);

        assertThat(restored.getVersion()).isEqualTo(2);
    }
}
