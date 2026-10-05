package com.saas.notification.activity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
public class ActivityLogMaintenanceConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock activityLogClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ActivityLogPartitionMaintenance activityLogPartitionMaintenance(DataSource dataSource, Clock activityLogClock) {
        return new ActivityLogPartitionMaintenance(dataSource, activityLogClock);
    }

    @Bean
    public ActivityLogMaintenanceScheduler activityLogMaintenanceScheduler(
            ActivityLogPartitionMaintenance maintenance,
            @Value("${saas.activity-log.retention-months:6}") int retentionMonths,
            @Value("${saas.activity-log.row-cleanup.enabled:false}") boolean rowCleanupEnabled,
            @Value("${saas.activity-log.row-cleanup.retention:PT10M}") Duration rowCleanupRetention) {
        return new ActivityLogMaintenanceScheduler(maintenance, retentionMonths, rowCleanupEnabled, rowCleanupRetention);
    }

    /** Daily partition roll + retention; the row-level cleanup is off unless a test profile turns it on (REQ-MON-03). */
    public static class ActivityLogMaintenanceScheduler {
        private final ActivityLogPartitionMaintenance maintenance;
        private final int retentionMonths;
        private final boolean rowCleanupEnabled;
        private final Duration rowCleanupRetention;

        ActivityLogMaintenanceScheduler(ActivityLogPartitionMaintenance maintenance, int retentionMonths,
                boolean rowCleanupEnabled, Duration rowCleanupRetention) {
            this.maintenance = maintenance;
            this.retentionMonths = retentionMonths;
            this.rowCleanupEnabled = rowCleanupEnabled;
            this.rowCleanupRetention = rowCleanupRetention;
        }

        @Scheduled(cron = "${saas.activity-log.maintenance-cron:0 0 2 * * *}")
        public void maintain() {
            maintenance.ensureUpcomingPartitions();
            maintenance.dropPartitionsOlderThan(retentionMonths);
            if (rowCleanupEnabled) {
                maintenance.deleteRowsOlderThan(rowCleanupRetention);
            }
        }

        @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
        public void ensurePartitionsAtStartup() {
            maintenance.ensureUpcomingPartitions();
        }
    }
}
