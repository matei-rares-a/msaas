package com.saas.shared.events.consumer;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Idempotency guard for event consumers (REQ-EVT-05): a redelivered eventId is recognized and the
 * handler is skipped. Call {@link #markProcessed(UUID)} as the first statement of the consumer's own
 * transaction, so it commits or rolls back together with the business change it guards.
 */
public class ProcessedEventStore {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** @return true the first time this eventId is seen (now recorded); false if already processed. */
    public boolean markProcessed(UUID eventId, UUID tenantId) {
        try {
            jdbcTemplate.update("INSERT INTO processed_events (id, tenant_id) VALUES (?, ?)", eventId, tenantId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
