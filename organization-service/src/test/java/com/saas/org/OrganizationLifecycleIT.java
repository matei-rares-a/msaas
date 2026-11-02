package com.saas.org;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real topology script and migrations against a dedicated container (with the real
 * org_svc/org_auth_reader roles), so the full create-organization -> rename -> internal lookup flow
 * is exercised exactly as in production, not against the generic @ServiceConnection container.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // distinct from OrganizationPermissionMatrixIT's marker: both classes set the same system
        // properties in their own static initializer, so Spring's context cache must not conflate them
        "test.marker=OrganizationLifecycleIT"
})
@AutoConfigureMockMvc
class OrganizationLifecycleIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    // started eagerly (not in @BeforeAll): @DynamicPropertySource can run before JUnit's @BeforeAll
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

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

        // System properties (set once per JVM, before SpringApplication.run() reads them) take
        // precedence over application.yml reliably; @DynamicPropertySource unexpectedly did not here.
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
    @Tag("REQ-AUTH-03")
    void creatingAnOrganizationMakesTheCreatorAdmin() throws Exception {
        UUID creatorUserId = UUID.randomUUID();

        ResponseEntity<Map> created = createOrganization("Acme", creatorUserId);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = UUID.fromString((String) created.getBody().get("id"));

        Map<String, Object> membership = queryAsTenant(tenantId,
                "SELECT role, read_only FROM org.memberships WHERE tenant_id = ? AND user_id = ?",
                tenantId, creatorUserId);
        assertThat(membership.get("role")).isEqualTo("ADMIN");
        assertThat(membership.get("read_only")).isEqualTo(false);
    }

    @Test
    @Tag("REQ-ROLE-06")
    void aReadOnlyMemberGets403OnWrite() throws Exception {
        UUID creatorUserId = UUID.randomUUID();
        ResponseEntity<Map> created = createOrganization("ReadOnly Co", creatorUserId);
        UUID tenantId = UUID.fromString((String) created.getBody().get("id"));

        UUID readOnlyUserId = UUID.randomUUID();
        updateAsTenant(tenantId,
                "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, 'USER', true)",
                UUID.randomUUID(), tenantId, readOnlyUserId);

        mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                        .header("X-User-Id", readOnlyUserId.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalMembershipsEndpointListsTenantsAcrossAllTenants() throws Exception {
        UUID userId = UUID.randomUUID();
        createOrganization("First Org", userId);
        createOrganization("Second Org", userId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/users/{userId}/memberships", userId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.length()").value(2));
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void internalMembersEndpointListsTheIdsOfTheTenantsMembersWithTheGivenRolesOnly() throws Exception {
        UUID admin = UUID.randomUUID();
        UUID tenantId = UUID.fromString((String) createOrganization("Members Co", admin).getBody().get("id"));
        UUID manager = addMember(tenantId, "MANAGER", false);
        UUID readOnlyManager = addMember(tenantId, "MANAGER", true);
        UUID user = addMember(tenantId, "USER", false);
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenant = UUID.fromString((String) createOrganization("Other Co", otherAdmin).getBody().get("id"));

        var approvers = membersOf(tenantId, "ADMIN,MANAGER");
        var users = membersOf(tenantId, "USER");

        assertThat(approvers).containsExactlyInAnyOrder(admin.toString(), manager.toString(), readOnlyManager.toString());
        assertThat(users).containsExactly(user.toString());
        assertThat(membersOf(otherTenant, "ADMIN,MANAGER")).containsExactly(otherAdmin.toString());
        assertThat(membersOf(otherTenant, "USER")).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "roles={0} is refused")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OWNER", "ADMIN,,MANAGER", "admin", "ADMIN;MANAGER", ""})
    @Tag("REQ-NOTIF-03")
    void internalMembersEndpointRefusesARoleThatDoesNotExist(String roles) throws Exception {
        UUID tenantId = UUID.fromString((String) createOrganization("Roles Co", UUID.randomUUID()).getBody().get("id"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/tenants/{tenantId}/members", tenantId).param("roles", roles))
                .andExpect(status().isBadRequest());
    }

    private java.util.List<String> membersOf(UUID tenantId, String roles) throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/internal/tenants/{tenantId}/members", tenantId).param("roles", roles))
                .andExpect(status().isOk()).andReturn();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        java.util.List<String> ids = new java.util.ArrayList<>();
        body.get("userIds").forEach(id -> ids.add(id.asText()));
        return ids;
    }

    private UUID addMember(UUID tenantId, String role, boolean readOnly) throws Exception {
        UUID userId = UUID.randomUUID();
        updateAsTenant(tenantId, "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenantId, userId, role, readOnly);
        return userId;
    }

    private ResponseEntity<Map> createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .header("X-User-Id", creatorUserId.toString()).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return ResponseEntity.status(result.getResponse().getStatus()).body(body);
    }

    /** One explicit connection: set_config (session-level) must apply to the very next statement. */
    private Map<String, Object> queryAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    private void updateAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update(sql, args);
        }
    }
}
