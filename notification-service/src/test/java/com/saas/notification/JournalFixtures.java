package com.saas.notification;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Journal rows for the read-API tests. Entries are placed relative to the start of the current month, so
 * they always fall in the monthly partition the maintenance job guarantees exists, whatever day the
 * test runs on. The shared test Postgres user is a superuser, which bypasses RLS: what these tests prove
 * is therefore the reader's own explicit tenant filter, not Postgres RLS.
 */
final class JournalFixtures {

    static final Instant MONTH_START = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    private JournalFixtures() {
    }

    static UUID insert(JdbcTemplate jdbc, UUID tenantId, String eventType, UUID actor, String metadataJson, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notif.activity_log (id, tenant_id, event_type, actor_user_id, metadata, created_at)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, ?)", id, tenantId, eventType, actor, metadataJson, Timestamp.from(createdAt));
        return id;
    }
}
