package com.saas.shared.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tenant bound to the current thread. Set from the request token (P5.3) or from the event envelope
 * (P3.3); everything below reads it: Hibernate's tenant filter and the {@code app.tenant_id} setting
 * used by the RLS policies.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new MissingTenantContextException();
        }
        return tenantId;
    }

    public static <T> T callWith(UUID tenantId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runWith(UUID tenantId, Runnable action) {
        callWith(tenantId, () -> {
            action.run();
            return null;
        });
    }
}
