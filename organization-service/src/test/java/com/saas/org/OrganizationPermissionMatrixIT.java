package com.saas.org;

import com.saas.org.entity.Membership;
import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * (role, endpoint, status) matrix for every gated org endpoint (REQ-ROLE-01, REQ-ROLE-05, REQ-MGR-03,
 * REQ-MGR-05). Same dedicated-container setup as {@link OrganizationLifecycleIT}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=OrganizationPermissionMatrixIT"
})
@AutoConfigureMockMvc
class OrganizationPermissionMatrixIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    private UUID tenantId;
    private final Map<Membership.Role, UUID> membersByRole = new EnumMap<>(Membership.Role.class);

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
    void setUpTenantWithOneMemberPerRole() throws Exception {
        UUID adminId = UUID.randomUUID();
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .content("{\"name\":\"Matrix Co\",\"creatorUserId\":\"" + adminId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        tenantId = UUID.fromString((String) body.get("id"));
        membersByRole.put(Membership.Role.ADMIN, adminId);

        UUID managerId = UUID.randomUUID();
        insertMembership(managerId, Membership.Role.MANAGER);
        membersByRole.put(Membership.Role.MANAGER, managerId);

        UUID userId = UUID.randomUUID();
        insertMembership(userId, Membership.Role.USER);
        membersByRole.put(Membership.Role.USER, userId);
    }

    /** A token is per-tenant (REQ-AUTH-04): X-Tenant-Id (from the caller's token) must match the
     * path's tenantId, even though this ADMIN happens to also be a member of the other tenant. */
    @Test
    @Tag("REQ-AUTH-04")
    void aTokenForAnotherTenantIsRejected() throws Exception {
        UUID otherTenantAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganizationOwnedBy("Other Co", otherTenantAdmin);
        insertMembership(otherTenantAdmin, Membership.Role.ADMIN);

        mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                        .header("X-User-Id", otherTenantAdmin.toString())
                        .header("X-Tenant-Id", otherTenantId.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isForbidden());
    }

    private UUID createOrganizationOwnedBy(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"creatorUserId\":\"" + creatorUserId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    @ParameterizedTest(name = "{0} can rename the organization (basic write access, no management)")
    @EnumSource(Membership.Role.class)
    @Tag("REQ-ROLE-01")
    @Tag("REQ-ROLE-05")
    void anyNonReadOnlyRoleCanRename(Membership.Role role) throws Exception {
        mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                        .header("X-User-Id", membersByRole.get(role).toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} managing a role -> {1}")
    @MethodSource("roleManagementCases")
    @Tag("REQ-MGR-03")
    void onlyAdminManagesRoles(Membership.Role caller, HttpStatus expected) throws Exception {
        UUID target = membersByRole.get(Membership.Role.USER);

        mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, target)
                        .header("X-User-Id", membersByRole.get(caller).toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().is(expected.value()));
    }

    static Stream<Arguments> roleManagementCases() {
        return Stream.of(
                Arguments.of(Membership.Role.ADMIN, HttpStatus.OK),
                Arguments.of(Membership.Role.MANAGER, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.USER, HttpStatus.FORBIDDEN));
    }

    @Test
    @Tag("REQ-MGR-05")
    void managerCountIsNotConstrained() throws Exception {
        UUID admin = membersByRole.get(Membership.Role.ADMIN);
        UUID secondUser = UUID.randomUUID();
        insertMembership(secondUser, Membership.Role.USER);

        // promote a second member to MANAGER -> two MANAGER in the same tenant, no error
        mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, secondUser)
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"MANAGER\"}"))
                .andExpect(status().isOk());

        // demote the original MANAGER back to USER -> zero MANAGER, still no error
        mockMvc.perform(patch("/organizations/{tenantId}/members/{userId}/role", tenantId, membersByRole.get(Membership.Role.MANAGER))
                        .header("X-User-Id", admin.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk());
    }

    private void insertMembership(UUID userId, Membership.Role role) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, false)",
                    UUID.randomUUID(), tenantId, userId, role.name());
        }
    }
}
