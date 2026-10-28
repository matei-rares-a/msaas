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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * (caller role, invited role) -> 201/403 matrix (REQ-ROLE-02, REQ-ROLE-05, REQ-MGR-01), plus the
 * revoke/list guards (REQ-INV-02). Same dedicated-topology-container pattern as
 * {@code OrganizationPermissionMatrixIT}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=InvitationPermissionMatrixIT"
})
@AutoConfigureMockMvc
class InvitationPermissionMatrixIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    private UUID tenantId;
    private final Map<Membership.Role, UUID> membersByRole = new EnumMap<>(Membership.Role.class);
    private UUID readOnlyUserId;

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
                        .content("{\"name\":\"Invite Matrix Co\",\"creatorUserId\":\"" + adminId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        tenantId = UUID.fromString((String) body.get("id"));
        membersByRole.put(Membership.Role.ADMIN, adminId);

        // max_users starts out NULL (fail-closed, design.md section 1): give this tenant plenty of
        // headroom so the permission matrix below is not confused with the maxUsers rejection path
        // (that is InvitationLifecycleIT's job).
        updateAsTenant(tenantId, "UPDATE org.tenants SET max_users = 1000 WHERE id = ?", tenantId);

        UUID managerId = UUID.randomUUID();
        insertMembership(managerId, Membership.Role.MANAGER, false);
        membersByRole.put(Membership.Role.MANAGER, managerId);

        UUID userId = UUID.randomUUID();
        insertMembership(userId, Membership.Role.USER, false);
        membersByRole.put(Membership.Role.USER, userId);

        readOnlyUserId = UUID.randomUUID();
        insertMembership(readOnlyUserId, Membership.Role.USER, true);
    }

    @ParameterizedTest(name = "{0} inviting {1} -> {2}")
    @MethodSource("inviteMatrixCases")
    @Tag("REQ-ROLE-02")
    @Tag("REQ-ROLE-05")
    @Tag("REQ-MGR-01")
    void inviteMatrix(Membership.Role caller, Membership.Role invited, HttpStatus expected) throws Exception {
        mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", membersByRole.get(caller).toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"invitee@example.com\",\"role\":\"" + invited + "\"}"))
                .andExpect(status().is(expected.value()));
    }

    static Stream<Arguments> inviteMatrixCases() {
        return Stream.of(
                Arguments.of(Membership.Role.ADMIN, Membership.Role.ADMIN, HttpStatus.CREATED),
                Arguments.of(Membership.Role.ADMIN, Membership.Role.MANAGER, HttpStatus.CREATED),
                Arguments.of(Membership.Role.ADMIN, Membership.Role.USER, HttpStatus.CREATED),
                Arguments.of(Membership.Role.MANAGER, Membership.Role.ADMIN, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.MANAGER, Membership.Role.MANAGER, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.MANAGER, Membership.Role.USER, HttpStatus.CREATED),
                Arguments.of(Membership.Role.USER, Membership.Role.ADMIN, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.USER, Membership.Role.MANAGER, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.USER, Membership.Role.USER, HttpStatus.FORBIDDEN));
    }

    @Test
    @Tag("REQ-ROLE-05")
    void aReadOnlyMemberOfAnyRoleIsForbiddenFromInviting() throws Exception {
        mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", readOnlyUserId.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"invitee@example.com\",\"role\":\"USER\"}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "revoke by {0} -> {1}")
    @MethodSource("revokeMatrixCases")
    @Tag("REQ-INV-02")
    void revokeMatrix(Membership.Role caller, HttpStatus expected) throws Exception {
        UUID invitationId = createInvitationAsAdminAndReturnId();

        mockMvc.perform(delete("/organizations/{tenantId}/invitations/{invitationId}", tenantId, invitationId)
                        .header("X-User-Id", membersByRole.get(caller).toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().is(expected.value()));
    }

    static Stream<Arguments> revokeMatrixCases() {
        return Stream.of(
                Arguments.of(Membership.Role.ADMIN, HttpStatus.NO_CONTENT),
                Arguments.of(Membership.Role.MANAGER, HttpStatus.FORBIDDEN),
                Arguments.of(Membership.Role.USER, HttpStatus.FORBIDDEN));
    }

    @Test
    @Tag("REQ-INV-02")
    void aReadOnlyAdminCannotRevoke() throws Exception {
        UUID invitationId = createInvitationAsAdminAndReturnId();
        UUID readOnlyAdminId = UUID.randomUUID();
        insertMembership(readOnlyAdminId, Membership.Role.ADMIN, true);

        mockMvc.perform(delete("/organizations/{tenantId}/invitations/{invitationId}", tenantId, invitationId)
                        .header("X-User-Id", readOnlyAdminId.toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "list by {0} -> {1}")
    @MethodSource("listMatrixCases")
    @Tag("REQ-ROLE-05")
    void listMatrix(Membership.Role caller, HttpStatus expected) throws Exception {
        mockMvc.perform(get("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", membersByRole.get(caller).toString())
                        .header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().is(expected.value()));
    }

    static Stream<Arguments> listMatrixCases() {
        return Stream.of(
                Arguments.of(Membership.Role.ADMIN, HttpStatus.OK),
                Arguments.of(Membership.Role.MANAGER, HttpStatus.OK),
                Arguments.of(Membership.Role.USER, HttpStatus.FORBIDDEN));
    }

    private UUID createInvitationAsAdminAndReturnId() throws Exception {
        var result = mockMvc.perform(post("/organizations/{tenantId}/invitations", tenantId)
                        .header("X-User-Id", membersByRole.get(Membership.Role.ADMIN).toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"email\":\"revoke-target@example.com\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private void insertMembership(UUID userId, Membership.Role role, boolean readOnly) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantId, userId, role.name(), readOnly);
        }
    }

    private void updateAsTenant(UUID tenantId, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update(sql, args);
        }
    }
}
