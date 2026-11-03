package com.saas.org.lunch;

import com.saas.org.client.TenantAccessStatusClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Random;

/**
 * Wires lunch roulette's jobs (REQ-LUNCH-03, REQ-LUNCH-05). The knobs are defaults in {@code application.yml}
 * ({@code saas.lunch.*}); a cron of {@code -} switches a job off, which the tests that drive the jobs by hand rely on.
 * Scheduling itself is enabled by {@code AdminDemotionEscalationConfig}.
 */
@Configuration
public class LunchConfig {

    /** The randomness of the grouping; a test replaces it with a seeded one to get the same groups every time. */
    @Bean
    public Random lunchRandom() {
        return new Random();
    }

    @Bean
    public LunchRoundFinder lunchRoundFinder(
            @Value("${saas.org.auth-reader.datasource.url}") String url,
            @Value("${saas.org.auth-reader.datasource.username}") String username,
            @Value("${saas.org.auth-reader.datasource.password}") String password) {
        return new LunchRoundFinder(url, username, password);
    }

    @Bean
    public LunchJobs lunchJobs(LunchRoundFinder finder, LunchService service, TenantAccessStatusClient accessStatus, Clock clock) {
        return new LunchJobs(finder, service, accessStatus, clock);
    }

    @Bean
    public LunchScheduler lunchScheduler(LunchJobs jobs, @Value("${saas.lunch.retention:P90D}") Duration retention) {
        return new LunchScheduler(jobs, retention);
    }

    public static class LunchScheduler {
        private final LunchJobs jobs;
        private final Duration retention;

        LunchScheduler(LunchJobs jobs, Duration retention) {
            this.jobs = jobs;
            this.retention = retention;
        }

        @Scheduled(cron = "${saas.lunch.formation-cron:0 * * * * *}")
        public void formDueRounds() {
            jobs.formDueRounds();
        }

        @Scheduled(cron = "${saas.lunch.purge-cron:0 30 3 * * *}")
        public void purgeOldRounds() {
            jobs.purgeOldRounds(retention);
        }
    }
}
