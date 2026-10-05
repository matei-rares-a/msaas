package com.saas.shared.events.consumer;

import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.EventPayload;
import com.saas.shared.tenancy.TenantContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventConsumerSupportTest {

    private record SamplePayload(UUID subjectId) implements EventPayload {
    }

    @Test
    @Tag("REQ-EVT-06")
    void setsTenantContextFromATenantScopedEnvelopeAndClearsItAfter() {
        UUID tenantId = UUID.randomUUID();
        EventEnvelope<SamplePayload> envelope = EventEnvelope.forTenant(
                "TEST_EVENT", 1, tenantId, new SamplePayload(UUID.randomUUID()));

        UUID[] seenDuringHandling = new UUID[1];
        EventConsumerSupport.runWithTenantFromEnvelope(envelope, () -> seenDuringHandling[0] = TenantContext.require());

        assertThat(seenDuringHandling[0]).isEqualTo(tenantId);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @Tag("REQ-EVT-06")
    void leavesNoTenantContextForAnIdentityEnvelope() {
        EventEnvelope<SamplePayload> envelope = EventEnvelope.forIdentity(
                "TEST_IDENTITY_EVENT", 1, new SamplePayload(UUID.randomUUID()));

        boolean[] ranWithoutTenant = new boolean[1];
        EventConsumerSupport.runWithTenantFromEnvelope(envelope, () -> ranWithoutTenant[0] = TenantContext.current().isEmpty());

        assertThat(ranWithoutTenant[0]).isTrue();
    }
}
