package com.saas.shared.events;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRoutingKeyTest {

    private record TenantPayload(UUID subjectId) implements EventPayload {
    }

    private record IdentityPayload(UUID subjectUserId) implements IdentityEventPayload {
    }

    private record PlainIdentityPayload(UUID somethingElse) implements EventPayload {
    }

    @Test
    @Tag("REQ-EVT-10")
    void aTenantScopedEventIsKeyedByTenantId() {
        UUID tenantId = UUID.randomUUID();
        EventEnvelope<TenantPayload> envelope = EventEnvelope.forTenant(
                "TEST_TENANT_EVENT", 1, tenantId, new TenantPayload(UUID.randomUUID()));

        assertThat(EventRoutingKey.resolve(envelope)).isEqualTo(tenantId.toString());
    }

    @Test
    @Tag("REQ-EVT-10")
    void anIdentityEventIsKeyedByTheSubjectUser() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<IdentityPayload> envelope = EventEnvelope.forIdentity(
                "TEST_IDENTITY_EVENT", 1, new IdentityPayload(userId));

        assertThat(EventRoutingKey.resolve(envelope)).isEqualTo(userId.toString());
    }

    @Test
    @Tag("REQ-EVT-10")
    void anIdentityEventWithoutAnIdentityPayloadHasNoRoutingKey() {
        EventEnvelope<PlainIdentityPayload> envelope = EventEnvelope.forIdentity(
                "TEST_BROKEN_EVENT", 1, new PlainIdentityPayload(UUID.randomUUID()));

        assertThatThrownBy(() -> EventRoutingKey.resolve(envelope)).isInstanceOf(IllegalStateException.class);
    }
}
