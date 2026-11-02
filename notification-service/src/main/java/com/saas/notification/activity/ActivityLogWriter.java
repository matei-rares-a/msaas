package com.saas.notification.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Appends one entry to the tenant activity journal (REQ-MON-01, REQ-MON-06). Plain JDBC on the
 * transaction the caller already opened, so the entry lands in the same tenant-scoped transaction as the
 * idempotency record (the tenant context was set when it started; RLS checks it on insert). Entries are
 * only ever appended - nothing here updates or deletes. The entry is stamped with the moment it is
 * recorded, read from the injected {@link Clock}.
 */
public class ActivityLogWriter {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ActivityLogWriter(DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** @param actorUserId null when the system acted (REQ-ROLE-04 escalation) */
    public void record(UUID tenantId, String eventType, UUID actorUserId, Map<String, Object> metadata) {
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize the metadata of a " + eventType + " journal entry", e);
        }
        jdbcTemplate.update("INSERT INTO notif.activity_log (id, tenant_id, event_type, actor_user_id, metadata, created_at)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                UUID.randomUUID(), tenantId, eventType, actorUserId, metadataJson, Timestamp.from(clock.instant()));
    }
}
