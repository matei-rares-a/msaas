package com.saas.org;

import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invitation lifecycle beyond the permission matrix (REQ-INV-01, REQ-INV-02, REQ-BILL-03,
 * REQ-EVT-02, REQ-EVT-09): expiry (platform default and per-tenant override), revocation, the
 * maxUsers limit, acceptance, and the PII/RabbitMQ-only boundary on the invitation email. Same
 * dedicated-topology-container pattern as the other org ITs, with a {@link MutableClock} so
 * expirations do not require sleeping.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=InvitationLifecycleIT"
})
@AutoConfigureMockMvc
class InvitationLifecycleIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
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
                .withEnv("ORG_AUTH_READER_DB_PASSWORD", "pw");
        container.start();

        String shardUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_shard_1");
        System.setProperty("spring.datasource.url", shardUrl);
        System.setProperty("spring.datasource.username", "org_svc");
        System.setProperty("spring.datasource.password", "pw");
        System.setProperty("saas.org.auth-reader.datasource.url", shardUrl);
        System.setProperty("saas.org.auth-reader.datasource.username", "org_auth_reader");
        System.setProperty("saas.org.auth-reader.datasource.password", "pw");
        return container;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Tag("REQ-INV-01")
    void anExpiredInvitationIsRejectedAtAccept() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Expiry Co", admin);
        allowUpTo(tenantId, 1000);

        String token = createInvitationAndReturnToken(tenantId, admin, "invitee@example.com", "USER");

        clock.advance(Duration.ofDays(8)); // past the 7-day platform default

        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"acceptingUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-INV-01")
    void perTenantExpiryOverrideIsUsedInsteadOfTheDefault() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Override Co", admin);
        allowUpTo(tenantId, 1000);

        mockMvc.perform(patch("/organizations/{tenantId}/invitation-expiry", tenantId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"expiryDays\":1}"))
                .andExpect(status().isNoContent());
        String overriddenToken = createInvitationAndReturnToken(tenantId, admin, "a@example.com", "USER");

        UUID defaultAdmin = UUID.randomUUID();
        UUID defaultTenantId = createOrganization("Default Co", defaultAdmin);
        allowUpTo(defaultTenantId, 1000);
        String defaultToken = createInvitationAndReturnToken(defaultTenantId, defaultAdmin, "b@example.com", "USER");

        clock.advance(Duration.ofDays(2));

        // overridden tenant: 1-day expiry, 2 days elapsed -> expired
        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + overriddenToken + "\",\"acceptingUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());

        // default tenant: 7-day platform default, 2 days elapsed -> still valid
        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + defaultToken + "\",\"acceptingUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("REQ-INV-01")
    @Tag("REQ-INV-02")
    void anExpiredPendingInvitationIsShownAsExpiredButCanStillBeRevoked() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Stale Co", admin);
        allowUpTo(tenantId, 1000);

        UUID invitationId = createInvitationAndReturnId(tenantId, admin, "stale@example.com", "USER");

        clock.advance(Duration.ofDays(8));

        var result = mockMvc.perform(get("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andReturn();
        List<Map> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                result.getResponse().getContentAsString(), List.class);
        assertThat(body).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(invitationId.toString());
            assertThat(row.get("status")).isEqualTo("EXPIRED");
        });

        mockMvc.perform(delete("/organizations/{tenantId}/invitations/{invitationId}", tenantId, invitationId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    @Tag("REQ-INV-02")
    void aRevokedInvitationIsRejectedAtAccept() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Revoke Co", admin);
        allowUpTo(tenantId, 1000);

        UUID invitationId = createInvitationAndReturnId(tenantId, admin, "revoked@example.com", "USER");
        String token = tokenOf(tenantId, invitationId);

        mockMvc.perform(delete("/organizations/{tenantId}/invitations/{invitationId}", tenantId, invitationId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"acceptingUserId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-BILL-03")
    void invitingOverMaxUsersIsRejected() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Limited Co", admin);
        // 1 occupant already (the creator/ADMIN); allow exactly 1 -> no room for another invite
        allowUpTo(tenantId, 1);

        mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"over-limit@example.com\",\"role\":\"USER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-BILL-03")
    void aTenantWithNoSubscriptionEventYetBlocksInvitations() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("No Subscription Co", admin);
        // max_users is left NULL (no SUBSCRIPTION_CHANGED ever processed) - fail-closed.

        mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"blocked@example.com\",\"role\":\"USER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-EVT-09")
    void acceptingAnInvitationCreatesAMembershipWithTheInvitedRole() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Accept Co", admin);
        allowUpTo(tenantId, 1000);

        String token = createInvitationAndReturnToken(tenantId, admin, "newmember@example.com", "MANAGER");
        UUID acceptingUserId = UUID.randomUUID();

        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + token + "\",\"acceptingUserId\":\"" + acceptingUserId + "\"}"))
                .andExpect(status().isOk());

        Map<String, Object> membership = queryAsTenant(tenantId,
                "SELECT role FROM org.memberships WHERE tenant_id = ? AND user_id = ?", tenantId, acceptingUserId);
        assertThat(membership.get("role")).isEqualTo("MANAGER");
    }

    @Test
    @Tag("REQ-EVT-09")
    void acceptingASecondInvitationForAnAlreadyExistingMemberIsRejected() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Double Invite Co", admin);
        allowUpTo(tenantId, 1000);

        UUID acceptingUserId = UUID.randomUUID();
        String firstToken = createInvitationAndReturnToken(tenantId, admin, "dup@example.com", "USER");
        String secondToken = createInvitationAndReturnToken(tenantId, admin, "dup@example.com", "USER");

        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + firstToken + "\",\"acceptingUserId\":\"" + acceptingUserId + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/invitations/accept")
                        .contentType("application/json")
                        .content("{\"token\":\"" + secondToken + "\",\"acceptingUserId\":\"" + acceptingUserId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-EVT-02")
    void theInvitationEmailCommandCarriesNoDataOutsideRabbitMq() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Outbox Co", admin);
        allowUpTo(tenantId, 1000);

        createInvitationAndReturnId(tenantId, admin, "outbox@example.com", "USER");

        Map<String, Object> emailRow = queryAsTenant(tenantId,
                "SELECT destination, payload FROM org.outbox WHERE tenant_id = ? AND event_type = 'SEND_INVITATION_EMAIL'",
                tenantId);
        assertThat(emailRow.get("destination")).isEqualTo("RABBITMQ");
        assertThat((String) emailRow.get("payload")).contains("outbox@example.com");

        List<Map<String, Object>> kafkaRows = queryListAsTenant(tenantId,
                "SELECT payload FROM org.outbox WHERE tenant_id = ? AND destination = 'KAFKA'", tenantId);
        assertThat(kafkaRows).noneMatch(row -> ((String) row.get("payload")).contains("outbox@example.com"));
    }

    private UUID createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"creatorUserId\":\"" + creatorUserId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Map body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private void allowUpTo(UUID tenantId, int maxUsers) throws Exception {
        updateAsTenant(tenantId, "UPDATE org.tenants SET max_users = ? WHERE id = ?", maxUsers, tenantId);
    }

    private String createInvitationAndReturnToken(UUID tenantId, UUID callerUserId, String email, String role) throws Exception {
        UUID invitationId = createInvitationAndReturnId(tenantId, callerUserId, email, role);
        return tokenOf(tenantId, invitationId);
    }

    private UUID createInvitationAndReturnId(UUID tenantId, UUID callerUserId, String email, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", callerUserId.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Map body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private String tokenOf(UUID tenantId, UUID invitationId) throws Exception {
        return (String) queryAsTenant(tenantId,
                "SELECT token FROM org.invitations WHERE tenant_id = ? AND id = ?", tenantId, invitationId).get("token");
    }

    private Map<String, Object> queryAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    private List<Map<String, Object>> queryListAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForList(sql, args);
        }
    }

    private void updateAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update(sql, args);
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
