package com.saas.org;

import com.saas.org.entity.Membership;
import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Offboarding one user from one tenant (REQ-DATA-01): the membership goes, the accepted invitation's
 * email is anonymized, the tenant of the same user elsewhere is untouched, and the event published for
 * the other services' cleanup carries ids only (REQ-EVT-02). Same dedicated-container setup as the
 * other org ITs.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=OffboardingIT"
})
@AutoConfigureMockMvc
class OffboardingIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final String REAL_EMAIL = "former.member@example.com";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    private UUID tenantId;
    private UUID adminId;
    private UUID userId;

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

    @BeforeEach
    void setUpTenantWithAnAdminAndAUser() throws Exception {
        adminId = UUID.randomUUID();
        tenantId = createOrganization("Offboard Co", adminId);
        userId = UUID.randomUUID();
        insertMembership(tenantId, userId, Membership.Role.USER);
    }

    @Test
    @Tag("REQ-DATA-01")
    void offboardingRemovesTheMembershipAnonymizesTheInvitationEmailAndKeepsTheRow() throws Exception {
        UUID invitationId = insertAcceptedInvitation(tenantId, userId, REAL_EMAIL);

        offboard(tenantId, adminId, userId).andExpect(status().isNoContent());

        assertThat(queryList(tenantId, "SELECT 1 FROM org.memberships WHERE tenant_id = ? AND user_id = ?", tenantId, userId))
                .isEmpty();
        Map<String, Object> invitation = query(tenantId,
                "SELECT email, status FROM org.invitations WHERE tenant_id = ? AND id = ?", tenantId, invitationId);
        assertThat((String) invitation.get("email")).doesNotContain(REAL_EMAIL).endsWith("@anonymized.invalid");
        assertThat(invitation.get("status")).isEqualTo("ACCEPTED");
    }

    @Test
    @Tag("REQ-DATA-01")
    void offboardingFromOneTenantLeavesTheUsersOtherTenantsUntouched() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);
        insertMembership(otherTenantId, userId, Membership.Role.USER);

        offboard(tenantId, adminId, userId).andExpect(status().isNoContent());

        mockMvc.perform(get("/internal/users/{userId}/memberships", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tenantId").value(otherTenantId.toString()));
    }

    @Test
    @Tag("REQ-EVT-02")
    void theOffboardingEventCarriesIdsOnly() throws Exception {
        insertAcceptedInvitation(tenantId, userId, REAL_EMAIL);

        offboard(tenantId, adminId, userId).andExpect(status().isNoContent());

        Map<String, Object> row = query(tenantId,
                "SELECT destination, payload FROM org.outbox WHERE tenant_id = ? AND event_type = 'MEMBER_OFFBOARDED'", tenantId);
        assertThat(row.get("destination")).isEqualTo("KAFKA");
        String payload = (String) row.get("payload");
        assertThat(payload).contains(tenantId.toString()).contains(userId.toString()).doesNotContain("@");
    }

    @ParameterizedTest(name = "{0} cannot offboard anyone")
    @EnumSource(value = Membership.Role.class, names = {"MANAGER", "USER"})
    @Tag("REQ-DATA-01")
    void onlyAnAdminCanOffboard(Membership.Role role) throws Exception {
        UUID caller = UUID.randomUUID();
        insertMembership(tenantId, caller, role);

        offboard(tenantId, caller, userId).andExpect(status().isForbidden());

        assertThat(queryList(tenantId, "SELECT 1 FROM org.memberships WHERE tenant_id = ? AND user_id = ?", tenantId, userId))
                .hasSize(1);
    }

    @Test
    @Tag("REQ-DATA-01")
    void aReadOnlyAdminCannotOffboard() throws Exception {
        UUID readOnlyAdmin = UUID.randomUUID();
        update(tenantId, "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, 'ADMIN', true)",
                UUID.randomUUID(), tenantId, readOnlyAdmin);

        offboard(tenantId, readOnlyAdmin, userId).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-ROLE-03")
    void anAdminIsNeverOffboardedDirectlySoATenantCannotLoseItsLastAdmin() throws Exception {
        offboard(tenantId, adminId, adminId).andExpect(status().isConflict());

        assertThat(queryList(tenantId, "SELECT 1 FROM org.memberships WHERE tenant_id = ? AND role = 'ADMIN'", tenantId))
                .hasSize(1);
    }

    @Test
    @Tag("REQ-AUTH-04")
    void aTokenForAnotherTenantCannotOffboard() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Intruder Co", otherAdmin);

        mockMvc.perform(delete("/organizations/{tenantId}/members/{userId}", tenantId, userId)
                        .header("X-User-Id", otherAdmin.toString())
                        .header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-DATA-01")
    void offboardingAnUnknownMemberIsABadRequest() throws Exception {
        offboard(tenantId, adminId, UUID.randomUUID()).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions offboard(UUID tenant, UUID caller, UUID target) throws Exception {
        return mockMvc.perform(delete("/organizations/{tenantId}/members/{userId}", tenant, target)
                .header("X-User-Id", caller.toString())
                .header("X-Tenant-Id", tenant.toString()));
    }

    private UUID createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .header("X-User-Id", creatorUserId.toString()).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Map body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private void insertMembership(UUID tenant, UUID user, Membership.Role role) throws Exception {
        update(tenant, "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, false)",
                UUID.randomUUID(), tenant, user, role.name());
    }

    private UUID insertAcceptedInvitation(UUID tenant, UUID acceptedBy, String email) throws Exception {
        UUID id = UUID.randomUUID();
        update(tenant, "INSERT INTO org.invitations (id, tenant_id, email, role, token, expires_at, status, accepted_by) "
                        + "VALUES (?, ?, ?, 'USER', ?, now() + interval '7 days', 'ACCEPTED', ?)",
                id, tenant, email, UUID.randomUUID().toString(), acceptedBy);
        return id;
    }

    private Map<String, Object> query(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    private List<Map<String, Object>> queryList(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            return jdbc.queryForList(sql, args);
        }
    }

    private void update(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            jdbc.update(sql, args);
        }
    }
}
