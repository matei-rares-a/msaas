package com.saas.notification.activity;

import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false",
        // the row-cleanup test sets app.tenant_id and inserts on the same connection; a single-connection
        // pool makes the session-level set_config stick for every statement in the test
        "spring.datasource.hikari.maximum-pool-size=1"
})
class ActivityLogPartitionMaintenanceIT implements PostgresTestContainer {

    @Autowired
    private ActivityLogPartitionMaintenance maintenance;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Tag("REQ-MON-06")
    void anEntryStoresTheGenericJournalShape() {
        maintenance.ensureUpcomingPartitions();
        UUID tenantId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");

        jdbc.update("INSERT INTO notif.activity_log (id, tenant_id, event_type, actor_user_id, metadata, created_at)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?)",
                id, tenantId, "ROLE_CHANGED", actorUserId, "{\"role\":\"ADMIN\"}", java.sql.Timestamp.from(clock.instant()));

        var row = jdbc.queryForMap("SELECT event_type, actor_user_id, tenant_id, metadata, created_at"
                + " FROM notif.activity_log WHERE id = ?", id);
        assertThat(row.get("event_type")).isEqualTo("ROLE_CHANGED");
        assertThat(row.get("actor_user_id")).isEqualTo(actorUserId);
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat(row.get("metadata").toString()).contains("ADMIN");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    @Tag("REQ-MON-07")
    void currentAndNextMonthPartitionsExist() {
        maintenance.ensureUpcomingPartitions();

        assertThat(maintenance.partitionExists(YearMonth.now(clock))).isTrue();
        assertThat(maintenance.partitionExists(YearMonth.now(clock).plusMonths(1))).isTrue();
    }

    @Test
    @Tag("REQ-MON-07")
    void partitionsOlderThanTheRetentionAreDropped() {
        maintenance.ensureUpcomingPartitions();
        YearMonth old = YearMonth.now(clock).minusMonths(8);
        createPartition(old);
        assertThat(maintenance.partitionExists(old)).isTrue();

        maintenance.dropPartitionsOlderThan(6);

        assertThat(maintenance.partitionExists(old)).isFalse();
        // still within the retention window
        assertThat(maintenance.partitionExists(YearMonth.now(clock))).isTrue();
    }

    @Test
    @Tag("REQ-MON-03")
    void theTestProfileRowCleanupDeletesOnlyOldRows() {
        maintenance.ensureUpcomingPartitions();
        UUID tenantId = UUID.randomUUID();
        UUID oldId = insertRow(tenantId, clock.instant().minus(Duration.ofMinutes(20)));
        UUID recentId = insertRow(tenantId, clock.instant().minus(Duration.ofMinutes(1)));

        int deleted = maintenance.deleteRowsOlderThan(Duration.ofMinutes(10));

        assertThat(deleted).isEqualTo(1);
        assertThat(rowExists(oldId)).isFalse();
        assertThat(rowExists(recentId)).isTrue();
    }

    private void createPartition(YearMonth month) {
        String name = String.format("activity_log_y%04dm%02d", month.getYear(), month.getMonthValue());
        var from = month.atDay(1);
        var to = month.plusMonths(1).atDay(1);
        jdbc.execute("CREATE TABLE notif." + name + " PARTITION OF notif.activity_log FOR VALUES FROM ('" + from + "') TO ('" + to + "')");
    }

    private UUID insertRow(UUID tenantId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
        jdbc.update("INSERT INTO notif.activity_log (id, tenant_id, event_type, created_at) VALUES (?, ?, 'TEST_EVENT', ?)",
                id, tenantId, java.sql.Timestamp.from(createdAt));
        return id;
    }

    private boolean rowExists(UUID id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notif.activity_log WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @org.springframework.context.annotation.Primary
        Clock clock() {
            return MutableClock.startingNow();
        }
    }
}
