package com.saas.org;

import com.saas.org.entity.Membership;
import com.saas.org.service.AdminDemotionEscalationJob;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Last-ADMIN protection (REQ-ROLE-03, REQ-ROLE-04). Same dedicated-container setup as the other org ITs. */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=AdminDemotionRequestIT"
})
@AutoConfigureMockMvc
class AdminDemotionRequestIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MutableClock clock;
    @Autowired
    private AdminDemotionEscalationJob escalationJob;

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
    @Tag("REQ-ROLE-03")
    void demotingTheOnlyAdminIsBlocked() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = createOrganization("Solo Admin Co", admin);

        mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, admin)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Tag("REQ-ROLE-04")
    void demotingOneOfTwoAdminsIsPendingUntilAccepted() throws Exception {
        UUID admin1 = UUID.randomUUID();
        UUID tenantId = createOrganization("Two Admins Co", admin1);
        UUID admin2 = UUID.randomUUID();
        insertMembership(tenantId, admin2, Membership.Role.ADMIN);

        var result = mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, admin2)
                        .header("X-User-Id", admin1.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID requestId = requestIdFrom(result);

        // still ADMIN: the demotion has not been applied yet
        assertThat(roleOf(tenantId, admin2)).isEqualTo("ADMIN");

        mockMvc.perform(post("/organizations/{tenantId}/admin-demotion-requests/{requestId}/accept", tenantId, requestId)
                        .header("X-User-Id", admin2.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());

        assertThat(roleOf(tenantId, admin2)).isEqualTo("USER");
    }

    @Test
    @Tag("REQ-ROLE-04")
    void rejectingADemotionRequestCancelsIt() throws Exception {
        UUID admin1 = UUID.randomUUID();
        UUID tenantId = createOrganization("Reject Co", admin1);
        UUID admin2 = UUID.randomUUID();
        insertMembership(tenantId, admin2, Membership.Role.ADMIN);

        var result = mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, admin2)
                        .header("X-User-Id", admin1.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID requestId = requestIdFrom(result);

        mockMvc.perform(post("/organizations/{tenantId}/admin-demotion-requests/{requestId}/reject", tenantId, requestId)
                        .header("X-User-Id", admin2.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());

        assertThat(roleOf(tenantId, admin2)).isEqualTo("ADMIN");
    }

    @Test
    @Tag("REQ-ROLE-04")
    void anOverdueRequestEscalatesAndNotifiesSupport() throws Exception {
        UUID admin1 = UUID.randomUUID();
        UUID tenantId = createOrganization("Escalation Co", admin1);
        UUID admin2 = UUID.randomUUID();
        insertMembership(tenantId, admin2, Membership.Role.ADMIN);

        var result = mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, admin2)
                        .header("X-User-Id", admin1.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID requestId = requestIdFrom(result);

        clock.advance(Duration.ofDays(8));
        int escalated = escalationJob.escalateOverdue(Duration.ofDays(7));
        assertThat(escalated).isEqualTo(1);

        assertThat(statusOf(tenantId, requestId)).isEqualTo("ESCALATED");
        assertThat(roleOf(tenantId, admin2)).isEqualTo("ADMIN");
        Map<String, Object> outboxRow = queryAsTenant(tenantId,
                "SELECT event_type, destination FROM org.outbox WHERE event_type = 'ADMIN_DEMOTION_ESCALATED'"
                        + " AND tenant_id = ?", tenantId);
        assertThat(outboxRow.get("destination")).isEqualTo("KAFKA");
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

    private UUID requestIdFrom(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        Map body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("requestId"));
    }

    private void insertMembership(UUID tenantId, UUID userId, Membership.Role role) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, false)",
                    UUID.randomUUID(), tenantId, userId, role.name());
        }
    }

    private String roleOf(UUID tenantId, UUID userId) throws Exception {
        return (String) queryAsTenant(tenantId,
                "SELECT role FROM org.memberships WHERE tenant_id = ? AND user_id = ?", tenantId, userId).get("role");
    }

    private String statusOf(UUID tenantId, UUID requestId) throws Exception {
        return (String) queryAsTenant(tenantId,
                "SELECT status FROM org.admin_demotion_requests WHERE tenant_id = ? AND id = ?", tenantId, requestId).get("status");
    }

    private Map<String, Object> queryAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @org.springframework.context.annotation.Primary
        Clock clock() {
            return MutableClock.startingNow();
        }
    }
}
