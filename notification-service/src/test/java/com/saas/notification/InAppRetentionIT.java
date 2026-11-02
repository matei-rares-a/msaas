package com.saas.notification;

import com.saas.notification.inapp.InAppRetentionJob;
import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-app notifications are deleted after 90 days, read or not, in every tenant (REQ-NOTIF-05), driven by a
 * controllable clock. The second half proves, as a role that is NOT a superuser (so RLS applies), that the
 * sweep's reach is exactly "rows older than the cutoff" and that no ordinary request gains anything from the
 * policies that allow it (REQ-DATA-03): the shared test Postgres user bypasses RLS, so without this the
 * policies would be untested.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
class InAppRetentionIT implements PostgresTestContainer {

    private static final String TESTER_ROLE = "inapp_rls_tester";

    @Autowired
    private InAppRetentionJob job;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Value("${saas.in-app.retention}")
    private Duration retention;

    @Test
    @Tag("REQ-NOTIF-05")
    void theRetentionIsNinetyDaysByDefault() {
        assertThat(retention).isEqualTo(Duration.ofDays(90));
    }

    @Test
    @Tag("REQ-NOTIF-05")
    void notificationsOlderThanNinetyDaysAreDeletedReadOrUnreadInEveryTenantAndNewerOnesStay() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Instant now = clock.instant();
        UUID oldUnreadA = insert(tenantA, now.minus(Duration.ofDays(91)), false);
        UUID oldReadA = insert(tenantA, now.minus(Duration.ofDays(120)), true);
        UUID oldUnreadB = insert(tenantB, now.minus(Duration.ofDays(91)), false);
        UUID recentA = insert(tenantA, now.minus(Duration.ofDays(89)), false);
        UUID recentReadB = insert(tenantB, now.minus(Duration.ofDays(10)), true);
        UUID brandNew = insert(tenantB, now, false);

        int deleted = job.run();

        assertThat(deleted).isGreaterThanOrEqualTo(3);
        assertThat(exists(oldUnreadA)).isFalse();
        assertThat(exists(oldReadA)).isFalse();
        assertThat(exists(oldUnreadB)).isFalse();
        assertThat(exists(recentA)).isTrue();
        assertThat(exists(recentReadB)).isTrue();
        assertThat(exists(brandNew)).isTrue();
    }

    @Test
    @Tag("REQ-NOTIF-05")
    void aNotificationLivesExactlyUntilItsNinetiethDayAndGoesWhenTheClockPassesIt() {
        UUID tenant = UUID.randomUUID();
        UUID id = insert(tenant, clock.instant().minus(Duration.ofDays(90)).plusSeconds(3600), false);

        job.run();
        assertThat(exists(id)).as("89 days and 23 hours old").isTrue();

        clock.advance(Duration.ofHours(2));
        job.run();
        assertThat(exists(id)).as("now over 90 days old").isFalse();
    }

    @Test
    @Tag("REQ-NOTIF-05")
    void aSweepWithNothingToDeleteDeletesNothing() {
        job.run();

        assertThat(job.run()).isZero();
    }

    // ---- the database side: what the policies allow, as a role RLS applies to (REQ-DATA-03) ----

    @Test
    @Tag("REQ-NOTIF-05")
    @Tag("REQ-DATA-03")
    void withoutATenantNorASweepSettingARoleSeesAndDeletesNothingEvenOldRows() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID old = insert(tenant, clock.instant().minus(Duration.ofDays(200)), false);

        asTester(tester -> {
            assertThat(tester.queryForObject("select count(*) from notif.in_app_notifications", Long.class)).isZero();
            assertThat(tester.update("delete from notif.in_app_notifications where created_at < now()")).isZero();
        });

        assertThat(exists(old)).isTrue();
    }

    @Test
    @Tag("REQ-NOTIF-05")
    @Tag("REQ-DATA-03")
    void withTheSweepSettingARoleReachesOnlyRowsOlderThanTheCutoffInAnyTenant() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Instant cutoff = clock.instant().minus(Duration.ofDays(90));
        UUID oldA = insert(tenantA, cutoff.minus(Duration.ofDays(5)), false);
        UUID oldB = insert(tenantB, cutoff.minus(Duration.ofDays(1)), true);
        UUID recentA = insert(tenantA, cutoff.plus(Duration.ofDays(1)), false);
        UUID recentB = insert(tenantB, cutoff.plus(Duration.ofDays(60)), false);

        asTester(tester -> {
            tester.queryForObject("select set_config('app.purge_before', ?, true)", String.class, cutoff.toString());
            var reachable = tester.queryForList("select id from notif.in_app_notifications where id in (?, ?, ?, ?)", UUID.class,
                    oldA, oldB, recentA, recentB);
            assertThat(reachable).containsExactlyInAnyOrder(oldA, oldB);
            assertThat(tester.update("delete from notif.in_app_notifications where id in (?, ?, ?, ?)", oldA, oldB, recentA, recentB)).isEqualTo(2);
        });

        assertThat(exists(oldA)).as("the check was rolled back, the policy is what was tested").isTrue();
        assertThat(exists(recentA)).isTrue();
    }

    @Test
    @Tag("REQ-DATA-03")
    void aTenantStillSeesAndDeletesOnlyItsOwnRowsAndTheSweepPoliciesAddNothingForRecentOnes() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        Instant cutoff = clock.instant().minus(Duration.ofDays(90));
        UUID recentA = insert(tenantA, cutoff.plus(Duration.ofDays(1)), false);
        UUID recentB = insert(tenantB, cutoff.plus(Duration.ofDays(1)), false);

        asTester(tester -> {
            tester.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenantA.toString());
            assertThat(tester.queryForList("select id from notif.in_app_notifications where id in (?, ?)", UUID.class, recentA, recentB))
                    .containsExactly(recentA);
            assertThat(tester.update("delete from notif.in_app_notifications where id = ?", recentB)).isZero();
        });

        assertThat(exists(recentB)).isTrue();
    }

    /**
     * Runs the work as a role that is not a superuser, so RLS applies, inside a transaction that is always rolled
     * back: the role, the grants and anything the work does vanish with it, and {@code SET LOCAL} / {@code set_config(.., true)}
     * cannot leak into the pooled connection.
     */
    private void asTester(java.util.function.Consumer<JdbcTemplate> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate tester = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                tester.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + TESTER_ROLE + "') THEN"
                        + " CREATE ROLE " + TESTER_ROLE + " NOLOGIN; END IF; END $$");
                tester.execute("GRANT USAGE ON SCHEMA notif TO " + TESTER_ROLE);
                tester.execute("GRANT SELECT, DELETE ON notif.in_app_notifications TO " + TESTER_ROLE);
                tester.execute("SET LOCAL ROLE " + TESTER_ROLE);
                work.accept(tester);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }
    private UUID insert(UUID tenant, Instant createdAt, boolean read) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notif.in_app_notifications (id, tenant_id, user_id, title, message, level, is_read, created_at)"
                        + " VALUES (?, ?, ?, 'T', 'M', 'INFO', ?, ?)", id, tenant, UUID.randomUUID(), read, Timestamp.from(createdAt));
        return id;
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("select count(*) from notif.in_app_notifications where id = ?", Long.class, id) > 0;
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock clock() {
            return MutableClock.startingNow();
        }
    }
}
