package com.saas.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogWriter;
import com.saas.notification.events.ActivityLogListener;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires the activity journal writer and its Kafka listener (REQ-MON-01). The listener container factory
 * (with the dead-letter handling every consumer in this service shares, REQ-EVT-07) is the one declared in
 * {@link OrgEventsConsumerConfig}.
 */
@Configuration
public class ActivityLogConsumerConfig {

    /** The {@link Clock} is the one {@code ActivityLogMaintenanceConfig} provides (system UTC; a test swaps in a controllable one). */
    @Bean
    public ActivityLogWriter activityLogWriter(DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        return new ActivityLogWriter(dataSource, objectMapper, clock);
    }

    @Bean
    public ActivityLogListener activityLogListener(ActivityLogWriter writer, DataSource dataSource,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        return new ActivityLogListener(writer, new ProcessedEventStore(dataSource), objectMapper, transactionManager);
    }
}
