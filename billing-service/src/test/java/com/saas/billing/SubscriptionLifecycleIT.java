package com.saas.billing;

import com.saas.billing.entity.Subscription;
import com.saas.billing.payment.SimulatedPaymentGateway;
import com.saas.billing.service.GracePeriodEscalationJob;
import com.saas.billing.service.SubscriptionService;
import com.saas.billing.service.TrialConversionJob;
import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subscription lifecycle (REQ-BILL-02, REQ-BILL-06): trial, cancel, plan change, automatic conversion.
 * Same dedicated-container setup as organization-service's lifecycle tests (here: the real topology
 * script, so {@code billing_job_reader} exists; {@code global.plans} is created inline since this
 * service's own Flyway only manages the billing schema, not auth-service's global one).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class SubscriptionLifecycleIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final Duration TRIAL = Duration.ofDays(14);
    private static final Duration GRACE_PERIOD = Duration.ofDays(7);
    private static String globalUrl;

    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private TrialConversionJob trialConversionJob;
    @Autowired
    private GracePeriodEscalationJob gracePeriodEscalationJob;
    @Autowired
    private SimulatedPaymentGateway simulatedPaymentGateway;
    @Autowired
    private MutableClock clock;

    private static PostgreSQLContainer<?> startTopology() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/init/01-topology.sh")),
                        "/docker-entrypoint-initdb.d/01-topology.sh")
                .withEnv("AUTH_DB_PASSWORD", "pw")
                .withEnv("ORG_DB_PASSWORD", "pw")
                .withEnv("BILLING_DB_PASSWORD", "pw")
                .withEnv("NOTIF_DB_PASSWORD", "pw")
                .withEnv("OUTBOX_RELAY_DB_PASSWORD", "pw")
                .withEnv("ORG_AUTH_READER_DB_PASSWORD", "pw")
                .withEnv("BILLING_JOB_READER_DB_PASSWORD", "pw");
        container.start();

        String shardUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_shard_1");
        System.setProperty("spring.datasource.url", shardUrl);
        System.setProperty("spring.datasource.username", "billing_svc");
        System.setProperty("spring.datasource.password", "pw");
        System.setProperty("saas.billing.job-reader.datasource.username", "billing_job_reader");
        System.setProperty("saas.billing.job-reader.datasource.password", "pw");

        String globalUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_global");
        SubscriptionLifecycleIT.globalUrl = globalUrl;
        System.setProperty("saas.billing.global-catalog.datasource.url", globalUrl);
        System.setProperty("saas.billing.global-catalog.datasource.username", "billing_svc");
        System.setProperty("saas.billing.global-catalog.datasource.password", "pw");

        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "admin", "admin");
             var statement = connection.createStatement()) {
            // billing-service's own Flyway only manages the billing schema; global.plans is
            // auth-service's migration (P6.1), created here directly since auth-service is not running.
            statement.execute("CREATE TABLE IF NOT EXISTS global.plans ("
                    + "id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL, price_eur NUMERIC(10,2) NOT NULL,"
                    + " max_users INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW())");
            statement.execute("GRANT SELECT ON global.plans TO billing_svc");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return container;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetGateway() {
        // Shared singleton across every test in this class; never leave it failing for the next test.
        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_SUCCEED);
    }

    @Test
    @Tag("REQ-BILL-06")
    void subscribingStartsATrial() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);

        Subscription subscription = subscriptionService.subscribe(tenantId, planId);

        assertThat(subscription.getStatus()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(subscription.getPlanId()).isEqualTo(planId);
    }

    @Test
    @Tag("REQ-BILL-02")
    void aSecondSubscriptionIsRejectedWhileOneIsCurrent() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        subscriptionService.subscribe(tenantId, planId);

        assertThatThrownBy(() -> subscriptionService.subscribe(tenantId, planId))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @Tag("REQ-BILL-02")
    void changingPlanCancelsTheOldSubscriptionAndStartsANewTrial() {
        UUID tenantId = UUID.randomUUID();
        UUID oldPlanId = createActivePlan("Starter", "9.99", 5);
        UUID newPlanId = createActivePlan("Pro", "29.99", 20);
        Subscription original = subscriptionService.subscribe(tenantId, oldPlanId);

        Subscription changed = subscriptionService.changePlan(tenantId, newPlanId);

        assertThat(changed.getPlanId()).isEqualTo(newPlanId);
        assertThat(changed.getStatus()).isEqualTo(Subscription.Status.TRIALING);
        assertThat(changed.getId()).isNotEqualTo(original.getId());
        assertThat(statusOf(original.getId())).isEqualTo("CANCELED");
    }

    @Test
    @Tag("REQ-BILL-06")
    void anExpiredTrialConvertsToActiveWithASimulatedPayment() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        Subscription subscription = subscriptionService.subscribe(tenantId, planId);

        clock.advance(TRIAL.plusDays(1));
        trialConversionJob.convertExpiredTrials(TRIAL);

        assertThat(statusOf(subscription.getId())).isEqualTo("ACTIVE");
    }

    @Test
    @Tag("REQ-BILL-06")
    void cancelingDuringTheTrialStopsTheAutomaticConversion() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        Subscription subscription = subscriptionService.subscribe(tenantId, planId);
        subscriptionService.cancelSubscription(tenantId, subscription.getId());

        clock.advance(TRIAL.plusDays(1));
        trialConversionJob.convertExpiredTrials(TRIAL);

        assertThat(statusOf(subscription.getId())).isEqualTo("CANCELED");
    }

    @Test
    @Tag("REQ-BILL-04")
    void aFailedChargeGoesPastDueAndASuccessfulRetryRecovers() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        Subscription subscription = subscriptionService.subscribe(tenantId, planId);
        subscriptionService.markStatus(tenantId, subscription.getId(), Subscription.Status.ACTIVE);

        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_FAIL);
        subscriptionService.chargeCurrentPeriod(tenantId);
        assertThat(statusOf(subscription.getId())).isEqualTo("PAST_DUE");
        assertThat(subscriptionService.accessStatus(tenantId)).isEqualTo("READ_ONLY");

        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_SUCCEED);
        subscriptionService.chargeCurrentPeriod(tenantId);
        assertThat(statusOf(subscription.getId())).isEqualTo("ACTIVE");
        assertThat(subscriptionService.accessStatus(tenantId)).isEqualTo("ACTIVE");
    }

    @Test
    @Tag("REQ-BILL-04")
    void aGracePeriodOverSevenDaysSuspendsTheSubscription() {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        Subscription subscription = subscriptionService.subscribe(tenantId, planId);
        subscriptionService.markStatus(tenantId, subscription.getId(), Subscription.Status.ACTIVE);

        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_FAIL);
        subscriptionService.chargeCurrentPeriod(tenantId);
        assertThat(statusOf(subscription.getId())).isEqualTo("PAST_DUE");

        clock.advance(GRACE_PERIOD.plusDays(1));
        gracePeriodEscalationJob.suspendOverdueGracePeriods(GRACE_PERIOD);

        assertThat(statusOf(subscription.getId())).isEqualTo("SUSPENDED");
        assertThat(subscriptionService.accessStatus(tenantId)).isEqualTo("SUSPENDED");

        simulatedPaymentGateway.setFailureMode(SimulatedPaymentGateway.FailureMode.ALWAYS_SUCCEED);
    }

    @Test
    @Tag("REQ-BILL-04")
    void accessStatusDefaultsToActiveWithNoCurrentSubscription() {
        assertThat(subscriptionService.accessStatus(UUID.randomUUID())).isEqualTo("ACTIVE");
    }

    private UUID createActivePlan(String name, String priceEur, int maxUsers) {
        UUID id = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "admin", "admin");
             var statement = connection.prepareStatement(
                     "INSERT INTO global.plans (id, name, price_eur, max_users, active) VALUES (?, ?, ?, ?, true)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setBigDecimal(3, new BigDecimal(priceEur));
            statement.setInt(4, maxUsers);
            statement.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private String statusOf(UUID subscriptionId) {
        // Admin connection, not the pooled app one: set_config is session-level and a connection
        // pool does not guarantee the same physical connection across two separate jdbc calls.
        String shardUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/saas_shard_1");
        try (var connection = java.sql.DriverManager.getConnection(shardUrl, "admin", "admin");
             var statement = connection.prepareStatement("SELECT status FROM billing.subscriptions WHERE id = ?")) {
            statement.setObject(1, subscriptionId);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString("status");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
