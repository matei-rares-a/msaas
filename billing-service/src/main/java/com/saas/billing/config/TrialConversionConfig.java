package com.saas.billing.config;

import com.saas.billing.plan.PlanCatalogReader;
import com.saas.billing.repository.InvoiceRepository;
import com.saas.billing.service.ExpiredTrialFinder;
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

@Configuration
@EnableScheduling
public class TrialConversionConfig {

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
}
