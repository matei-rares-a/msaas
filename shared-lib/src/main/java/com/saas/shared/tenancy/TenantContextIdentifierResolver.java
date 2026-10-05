package com.saas.shared.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.UUID;

/**
 * Feeds {@link TenantContext} to Hibernate. Sessions are also opened outside any transaction (for
 * example while the context starts), so instead of failing there we hand out a tenant that matches no
 * row: reads return nothing. Writes always run in a transaction, where
 * {@link TenantAwareJpaTransactionManager} refuses to start without a tenant.
 */
public class TenantContextIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
