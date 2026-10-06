package com.saas.auth;

import com.saas.auth.entity.Plan;
import com.saas.auth.repository.PlanRepository;
import com.saas.auth.service.PlanCatalogService;
import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan catalog, global and EUR-only (REQ-DATA-05, REQ-BILL-07); never deleted, only deactivated
 * (REQ-DATA-12). Same dedicated-container setup as {@link RegistrationIT}.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class PlanCatalogIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private PlanCatalogService planCatalogService;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private JdbcTemplate jdbc;

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
                .withEnv("ORG_AUTH_READER_DB_PASSWORD", "pw");
        container.start();

        String globalUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_global");
        System.setProperty("spring.datasource.url", globalUrl);
        System.setProperty("spring.datasource.username", "auth_svc");
        System.setProperty("spring.datasource.password", "pw");
        return container;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Tag("REQ-BILL-07")
    void aPlanIsAlwaysPricedInEur() {
        Plan plan = planCatalogService.createPlan("Starter", new BigDecimal("19.99"), 5);

        assertThat(plan.getPriceEur()).isEqualByComparingTo("19.99");
    }

    @Test
    @Tag("REQ-DATA-12")
    void aDeactivatedPlanIsNoLongerOfferedButStillExists() {
        Plan plan = planCatalogService.createPlan("Legacy", new BigDecimal("9.99"), 3);

        assertThat(planCatalogService.listActivePlans()).extracting(Plan::getId).contains(plan.getId());

        planCatalogService.deactivatePlan(plan.getId());

        assertThat(planCatalogService.listActivePlans()).extracting(Plan::getId).doesNotContain(plan.getId());
        assertThat(planRepository.findById(plan.getId())).isPresent();
    }

    @Test
    @Tag("REQ-DATA-12")
    void deletingAPlanIsRejectedEvenAtTheDatabaseLevel() {
        Plan plan = planCatalogService.createPlan("Undeletable", new BigDecimal("4.99"), 1);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM global.plans WHERE id = ?", plan.getId()))
                .hasMessageContaining("plans are never deleted");
        assertThat(planRepository.findById(plan.getId())).isPresent();
    }

    @Test
    @Tag("REQ-DATA-05")
    void thePlanCatalogLivesInTheGlobalDatabaseNotTheShard() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'global' AND table_name = 'plans'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
