package com.saas.shared.events.consumer;

import com.saas.shared.events.EventEnvelope;
import com.saas.shared.tenancy.TenantContext;

/** Sets {@link TenantContext} from the envelope before the handler runs (REQ-EVT-06). */
public final class EventConsumerSupport {

    private EventConsumerSupport() {
    }

    public static void runWithTenantFromEnvelope(EventEnvelope<?> envelope, Runnable action) {
        if (envelope.isTenantScoped()) {
            TenantContext.runWith(envelope.getTenantId(), action);
        } else {
            action.run();
        }
    }
}
