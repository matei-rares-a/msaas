package com.saas.shared.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Writes an outbox row in the caller's own transaction (REQ-EVT-04): the row and the business change
 * either commit together or roll back together, so the relay only ever sees state that really happened.
 * Uses the service's own role and its ambient tenant transaction (app.tenant_id already set by
 * {@link com.saas.shared.tenancy.TenantAwareJpaTransactionManager}); the relay, not this class, bypasses RLS.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbcTemplate;

    public OutboxWriter(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * @param tenantId   null only for identity-level events (no tenant); must match the ambient
     *                   app.tenant_id for tenant-scoped services, otherwise the RLS check rejects the insert
     * @param payloadJson the serialized {@link com.saas.shared.events.EventEnvelope}
     */
    public UUID write(UUID tenantId, String eventType, OutboxDestination destination,
                       String channel, String routingKey, String payloadJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO outbox"
                        + " (id, tenant_id, event_type, destination, channel, routing_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, eventType, destination.name(), channel, routingKey, payloadJson);
        return id;
    }
}
