package com.saas.org.config;

import com.saas.org.service.AdminDemotionEscalationJob;
import com.saas.org.service.OrganizationService;
import com.saas.org.service.PendingAdminDemotionFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
public class AdminDemotionEscalationConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock organizationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PendingAdminDemotionFinder pendingAdminDemotionFinder(
            @Value("${saas.org.auth-reader.datasource.url}") String url,
            @Value("${saas.org.auth-reader.datasource.username}") String username,
            @Value("${saas.org.auth-reader.datasource.password}") String password) {
        return new PendingAdminDemotionFinder(url, username, password);
    }

    @Bean
    public AdminDemotionEscalationJob adminDemotionEscalationJob(PendingAdminDemotionFinder finder,
            OrganizationService organizationService, Clock organizationClock) {
        return new AdminDemotionEscalationJob(finder, organizationService, organizationClock);
    }

    @Bean
    public AdminDemotionEscalationScheduler adminDemotionEscalationScheduler(AdminDemotionEscalationJob job,
            @Value("${saas.admin-demotion.response-deadline:7d}") Duration responseDeadline) {
        return new AdminDemotionEscalationScheduler(job, responseDeadline);
    }

    public static class AdminDemotionEscalationScheduler {
        private final AdminDemotionEscalationJob job;
        private final Duration responseDeadline;

        AdminDemotionEscalationScheduler(AdminDemotionEscalationJob job, Duration responseDeadline) {
            this.job = job;
            this.responseDeadline = responseDeadline;
        }

        @Scheduled(cron = "${saas.admin-demotion.escalation-cron:0 0 3 * * *}")
        public void escalateOverdueRequests() {
            job.escalateOverdue(responseDeadline);
        }
    }
}
