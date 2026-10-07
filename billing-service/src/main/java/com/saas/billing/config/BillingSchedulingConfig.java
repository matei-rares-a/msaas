package com.saas.billing.config;

import com.saas.billing.plan.PlanCatalogReader;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.service.ExpiredTrialFinder;
import com.saas.billing.service.GracePeriodEscalationJob;
import com.saas.billing.service.PaymentService;
import com.saas.billing.service.SubscriptionService;
import com.saas.billing.service.TrialConversionJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/** Billing's scheduled jobs (REQ-BILL-06 trial conversion, REQ-BILL-04 grace-period escalation). */
@Configuration
@EnableScheduling
public class BillingSchedulingConfig {

    @Bean
    public ExpiredTrialFinder expiredTrialFinder(
            @Value("${saas.billing.job-reader.datasource.url}") String url,
            @Value("${saas.billing.job-reader.datasource.username}") String username,
            @Value("${saas.billing.job-reader.datasource.password}") String password) {
        // Not a Spring-managed DataSource bean: a second, unqualified one makes Flyway/JPA's
        // auto-configured DataSource lookup ambiguous and fails (same trap as org-service's readers).
        DataSource dataSource = DataSourceBuilder.create().url(url).username(username).password(password).build();
        return new ExpiredTrialFinder(dataSource);
    }

    @Bean
    public TrialConversionJob trialConversionJob(ExpiredTrialFinder finder, InvoiceRepository invoiceRepository,
            PaymentService paymentService, SubscriptionService subscriptionService, PlanCatalogReader planCatalogReader,
            Clock clock, PlatformTransactionManager transactionManager) {
        return new TrialConversionJob(finder, invoiceRepository, paymentService, subscriptionService,
                planCatalogReader, clock, transactionManager);
    }

    @Bean
    public TrialConversionScheduler trialConversionScheduler(TrialConversionJob job,
            @Value("${saas.billing.trial-days:14}") long trialDays) {
        return new TrialConversionScheduler(job, Duration.ofDays(trialDays));
    }

    @Bean
    public GracePeriodEscalationJob gracePeriodEscalationJob(ExpiredTrialFinder finder,
            SubscriptionService subscriptionService, Clock clock) {
        return new GracePeriodEscalationJob(finder, subscriptionService, clock);
    }

    @Bean
    public GracePeriodEscalationScheduler gracePeriodEscalationScheduler(GracePeriodEscalationJob job,
            @Value("${saas.billing.grace-period-days:7}") long gracePeriodDays) {
        return new GracePeriodEscalationScheduler(job, Duration.ofDays(gracePeriodDays));
    }

    public static class TrialConversionScheduler {
        private final TrialConversionJob job;
        private final Duration trialDuration;

        TrialConversionScheduler(TrialConversionJob job, Duration trialDuration) {
            this.job = job;
            this.trialDuration = trialDuration;
        }

        @Scheduled(cron = "${saas.billing.trial-conversion-cron:0 0 4 * * *}")
        public void convertExpiredTrials() {
            job.convertExpiredTrials(trialDuration);
        }
    }

    public static class GracePeriodEscalationScheduler {
        private final GracePeriodEscalationJob job;
        private final Duration gracePeriod;

        GracePeriodEscalationScheduler(GracePeriodEscalationJob job, Duration gracePeriod) {
            this.job = job;
            this.gracePeriod = gracePeriod;
        }

        @Scheduled(cron = "${saas.billing.grace-period-cron:0 30 4 * * *}")
        public void suspendOverdueGracePeriods() {
            job.suspendOverdueGracePeriods(gracePeriod);
        }
    }
}
