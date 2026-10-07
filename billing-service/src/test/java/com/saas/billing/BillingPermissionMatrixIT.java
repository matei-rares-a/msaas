package com.saas.billing;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * (role, endpoint, status) matrix for every billing endpoint (REQ-ROLE-01, REQ-MGR-02): ADMIN has
 * every privilege, MANAGER can view but not act, USER has no access at all. Same dedicated-container
 * setup as {@code SubscriptionLifecycleIT} (global.plans created inline).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
class BillingPermissionMatrixIT {

    private enum Role {ADMIN, MANAGER, USER}

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static String globalUrl;

    @Autowired
    private MockMvc mockMvc;

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
        BillingPermissionMatrixIT.globalUrl = globalUrl;
        System.setProperty("saas.billing.global-catalog.datasource.url", globalUrl);
        System.setProperty("saas.billing.global-catalog.datasource.username", "billing_svc");
        System.setProperty("saas.billing.global-catalog.datasource.password", "pw");

        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "admin", "admin");
             var statement = connection.createStatement()) {
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

    @ParameterizedTest(name = "{0} viewing the subscription -> {0}")
    @EnumSource(Role.class)
    @Tag("REQ-ROLE-01")
    @Tag("REQ-MGR-02")
    void viewingTheSubscription(Role role) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        subscribeAsAdmin(tenantId, planId);

        HttpStatus expected = role == Role.USER ? HttpStatus.FORBIDDEN : HttpStatus.OK;
        mockMvc.perform(get("/billing/subscription")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Role", role.name()))
                .andExpect(status().is(expected.value()));
    }

    @ParameterizedTest(name = "{0} viewing invoices -> {0}")
    @EnumSource(Role.class)
    @Tag("REQ-ROLE-01")
    @Tag("REQ-MGR-02")
    void viewingInvoices(Role role) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        subscribeAsAdmin(tenantId, planId);

        HttpStatus expected = role == Role.USER ? HttpStatus.FORBIDDEN : HttpStatus.OK;
        mockMvc.perform(get("/billing/invoices")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Role", role.name()))
                .andExpect(status().is(expected.value()));
    }

    @ParameterizedTest(name = "{0} subscribing -> {0}")
    @EnumSource(Role.class)
    @Tag("REQ-ROLE-01")
    @Tag("REQ-MGR-02")
    void subscribing(Role role) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);

        HttpStatus expected = role == Role.ADMIN ? HttpStatus.CREATED : HttpStatus.FORBIDDEN;
        mockMvc.perform(post("/billing/subscriptions")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Role", role.name())
                        .contentType("application/json")
                        .content("{\"planId\":\"" + planId + "\"}"))
                .andExpect(status().is(expected.value()));
    }

    @ParameterizedTest(name = "{0} canceling -> {0}")
    @EnumSource(Role.class)
    @Tag("REQ-ROLE-01")
    @Tag("REQ-MGR-02")
    void canceling(Role role) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID planId = createActivePlan("Starter", "9.99", 5);
        subscribeAsAdmin(tenantId, planId);

        HttpStatus expected = role == Role.ADMIN ? HttpStatus.OK : HttpStatus.FORBIDDEN;
        mockMvc.perform(post("/billing/subscriptions/cancel")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Role", role.name()))
                .andExpect(status().is(expected.value()));
    }

    private void subscribeAsAdmin(UUID tenantId, UUID planId) throws Exception {
        mockMvc.perform(post("/billing/subscriptions")
                        .header("X-Tenant-Id", tenantId.toString())
                        .header("X-Role", "ADMIN")
                        .contentType("application/json")
                        .content("{\"planId\":\"" + planId + "\"}"))
                .andExpect(status().isCreated());
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
}
