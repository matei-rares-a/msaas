package com.saas.notification.inapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the in-app notifications (REQ-NOTIF-01..05): the service, the rules and the Kafka listener that create
 * them, the directory that says who the admins and managers are, and the retention sweep. The knobs are defaults
 * in {@code application.yml} ({@code saas.in-app.*}, {@code org-service.base-url}).
 */
@Configuration
public class InAppNotificationConfig {

    @Bean
    public InAppNotificationService inAppNotificationService(InAppNotificationRepository repository,
            PlatformTransactionManager transactionManager, DataSource dataSource, Clock clock) {
        return new InAppNotificationService(repository, transactionManager, dataSource, clock);
    }

    @Bean
    public MemberDirectory memberDirectory(RestClient.Builder builder, @Value("${org-service.base-url}") String baseUrl,
            @Value("${saas.in-app.directory-timeout:PT5S}") Duration timeout) {
        return new OrgMemberDirectory(builder, baseUrl, timeout);
    }

    @Bean
    public InAppNotificationRules inAppNotificationRules(MemberDirectory memberDirectory, ObjectMapper objectMapper) {
        return new InAppNotificationRules(memberDirectory, objectMapper);
    }

    @Bean
    public InAppNotificationListener inAppNotificationListener(InAppNotificationRules rules, InAppNotificationService service,
            DataSource dataSource, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        return new InAppNotificationListener(rules, service, new ProcessedEventStore(dataSource), objectMapper, transactionManager);
    }

    @Bean
    public InAppRetentionJob inAppRetentionJob(InAppNotificationService service, Clock clock,
            @Value("${saas.in-app.retention:P90D}") Duration retention) {
        return new InAppRetentionJob(service, clock, retention);
    }

    @Bean
    public InAppRetentionScheduler inAppRetentionScheduler(InAppRetentionJob job) {
        return new InAppRetentionScheduler(job);
    }

    public static class InAppRetentionScheduler {
        private final InAppRetentionJob job;

        InAppRetentionScheduler(InAppRetentionJob job) {
            this.job = job;
        }

        @Scheduled(cron = "${saas.in-app.purge-cron:0 0 3 * * *}")
        public void purgeExpired() {
            job.run();
        }
    }
}
