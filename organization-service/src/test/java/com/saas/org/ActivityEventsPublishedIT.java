package com.saas.org;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.service.OrganizationService;
import com.saas.shared.events.ActivityEventCatalog;
import com.saas.testsupport.containers.SharedContainers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Producer side of the tenant journal (REQ-MON-01): every sensitive action org-service performs publishes
 * its event in the same transaction, to Kafka, with the acting user and the fields the shared
 * {@link ActivityEventCatalog} says the journal reads - and never an email. The catalog is the contract
 * with notification-service, so this test checks each published payload against it. Same
 * dedicated-container setup as {@link ThemeIT}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=ActivityEventsPublishedIT"
})
@AutoConfigureMockMvc
class ActivityEventsPublishedIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final Path STORAGE_ROOT = createStorageRoot();
    private static HttpServer billingStub = startBillingStub();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private OrganizationService organizationService;

    private UUID tenantId;
    private UUID adminId;
    private UUID userId;

    private static Path createStorageRoot() {
        try {
            Path dir = Files.createTempDirectory("msaas-activity-it");
            System.setProperty("saas.storage.local.root", dir.toString());
            return dir;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

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
        String globalUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_global");
        System.setProperty("spring.datasource.url", shardUrl);
        System.setProperty("spring.datasource.username", "org_svc");
        System.setProperty("spring.datasource.password", "pw");
        System.setProperty("saas.org.auth-reader.datasource.url", shardUrl);
        System.setProperty("saas.org.auth-reader.datasource.username", "org_auth_reader");
        System.setProperty("saas.org.auth-reader.datasource.password", "pw");
        System.setProperty("saas.org.global-catalog.datasource.url", globalUrl);
        System.setProperty("saas.org.global-catalog.datasource.username", "org_svc");
        System.setProperty("saas.org.global-catalog.datasource.password", "pw");

        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "admin", "admin");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS global.palettes (id VARCHAR(50) PRIMARY KEY, name VARCHAR(100) NOT NULL,"
                    + " kind VARCHAR(20) NOT NULL, definition JSONB NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE)");
            statement.execute("GRANT SELECT ON global.palettes TO org_svc");
            statement.execute("INSERT INTO global.palettes (id, name, kind, definition) VALUES"
                    + " ('default', 'Classic blue', 'COLOR', '{\"primary\":\"#1D4ED8\",\"secondary\":\"#475569\",\"accent\":\"#F59E0B\"}'),"
                    + " ('forest', 'Forest', 'COLOR', '{\"primary\":\"#15803D\",\"secondary\":\"#3F3F46\",\"accent\":\"#EAB308\"}')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return container;
    }

    private static HttpServer startBillingStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/tenants/", exchange -> {
                byte[] body = "{\"status\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            System.setProperty("billing-service.base-url", "http://localhost:" + server.getAddress().getPort());
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        if (billingStub != null) {
            billingStub.stop(0);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUpTenant() throws Exception {
        adminId = UUID.randomUUID();
        tenantId = createOrganization("Audit Co", adminId);
        userId = UUID.randomUUID();
        insertMembership(userId, "USER");
        update("UPDATE org.tenants SET max_users = 1000 WHERE id = ?", tenantId);
    }

    @Test
    @Tag("REQ-MON-01")
    void aRoleChangeAppliedAtOnceIsPublishedWithTheActorTheTargetAndBothRoles() throws Exception {
        changeRole(adminId, userId, "MANAGER").andExpect(status().isOk());

        JsonNode payload = publishedPayload("MEMBER_ROLE_CHANGED", adminId);
        assertThat(payload.get("targetUserId").asText()).isEqualTo(userId.toString());
        assertThat(payload.get("previousRole").asText()).isEqualTo("USER");
        assertThat(payload.get("newRole").asText()).isEqualTo("MANAGER");
    }

    @Test
    @Tag("REQ-MON-01")
    void theWholeLastAdminDemotionFlowIsPublishedRequestThenTheTargetsDecision() throws Exception {
        UUID secondAdmin = UUID.randomUUID();
        insertMembership(secondAdmin, "ADMIN");

        MvcResultHolder requested = new MvcResultHolder(changeRole(adminId, secondAdmin, "USER").andExpect(status().isAccepted()));
        UUID requestId = requested.uuid("requestId");
        JsonNode requestedPayload = publishedPayload("ADMIN_DEMOTION_REQUESTED", adminId);
        assertThat(requestedPayload.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(requestedPayload.get("targetUserId").asText()).isEqualTo(secondAdmin.toString());
        assertThat(requestedPayload.get("newRole").asText()).isEqualTo("USER");

        mockMvc.perform(post("/organizations/{t}/admin-demotion-requests/{r}/reject", tenantId, requestId)
                        .header("X-User-Id", secondAdmin.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());
        JsonNode rejected = publishedPayload("ADMIN_DEMOTION_REJECTED", secondAdmin);
        assertThat(rejected.get("requestId").asText()).isEqualTo(requestId.toString());

        UUID secondRequest = new MvcResultHolder(changeRole(adminId, secondAdmin, "MANAGER").andExpect(status().isAccepted())).uuid("requestId");
        mockMvc.perform(post("/organizations/{t}/admin-demotion-requests/{r}/accept", tenantId, secondRequest)
                        .header("X-User-Id", secondAdmin.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());
        JsonNode accepted = publishedPayload("ADMIN_DEMOTION_ACCEPTED", secondAdmin);
        assertThat(accepted.get("requestId").asText()).isEqualTo(secondRequest.toString());
        assertThat(accepted.get("newRole").asText()).isEqualTo("MANAGER");
    }

    @Test
    @Tag("REQ-MON-01")
    void anEscalatedDemotionIsPublishedWithNoActorBecauseTheSystemDidIt() throws Exception {
        UUID secondAdmin = UUID.randomUUID();
        insertMembership(secondAdmin, "ADMIN");
        UUID requestId = new MvcResultHolder(changeRole(adminId, secondAdmin, "USER").andExpect(status().isAccepted())).uuid("requestId");

        organizationService.escalateAdminDemotion(tenantId, requestId);

        JsonNode payload = publishedPayload("ADMIN_DEMOTION_ESCALATED", null);
        assertThat(payload.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(payload.get("targetUserId").asText()).isEqualTo(secondAdmin.toString());
        assertThat(payload.get("requestedBy").asText()).isEqualTo(adminId.toString());
    }

    @Test
    @Tag("REQ-MON-01")
    void creatingAndRevokingAnInvitationArePublishedWithoutTheInviteesEmail() throws Exception {
        String email = "secret.invitee@example.com";
        var created = mockMvc.perform(post("/organizations/{t}/invitations", tenantId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"email\":\"" + email + "\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID invitationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());

        JsonNode createdPayload = publishedPayload("INVITATION_CREATED", adminId);
        assertThat(createdPayload.get("invitationId").asText()).isEqualTo(invitationId.toString());
        assertThat(createdPayload.get("role").asText()).isEqualTo("USER");

        mockMvc.perform(delete("/organizations/{t}/invitations/{i}", tenantId, invitationId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());
        JsonNode revoked = publishedPayload("INVITATION_REVOKED", adminId);
        assertThat(revoked.get("invitationId").asText()).isEqualTo(invitationId.toString());

        // the only Kafka rows of this tenant carry no address; the email travels in the RabbitMQ command only
        assertThat(kafkaPayloads()).noneMatch(json -> json.contains(email) || json.contains("@"));
    }

    @Test
    @Tag("REQ-MON-01")
    void anAcceptedInvitationIsPublishedWithTheAcceptingUserAsActor() throws Exception {
        var created = mockMvc.perform(post("/organizations/{t}/invitations", tenantId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"email\":\"joiner@example.com\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated()).andReturn();
        UUID invitationId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
        String token = (String) query("SELECT token FROM org.invitations WHERE id = ?", invitationId).get("token");
        UUID joiner = UUID.randomUUID();

        mockMvc.perform(post("/invitations/accept").header("X-User-Id", joiner.toString())
                        .contentType("application/json").content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk());

        JsonNode payload = publishedPayload("INVITATION_ACCEPTED", joiner);
        assertThat(payload.get("invitationId").asText()).isEqualTo(invitationId.toString());
        assertThat(payload.get("role").asText()).isEqualTo("MANAGER");
    }

    @Test
    @Tag("REQ-MON-01")
    void aThemeChangeAndALogoChangeArePublished() throws Exception {
        mockMvc.perform(put("/organizations/{t}/theme", tenantId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"paletteId\":\"forest\"}"))
                .andExpect(status().isOk());
        assertThat(publishedPayload("THEME_CHANGED", adminId).get("paletteId").asText()).isEqualTo("forest");

        mockMvc.perform(multipart("/organizations/{t}/theme/logo", tenantId)
                        .file(new MockMultipartFile("file", "logo.png", "image/png", TestImages.image("png", 16, 16)))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());
        publishedPayload("LOGO_CHANGED", adminId);
    }

    @Test
    @Tag("REQ-MON-01")
    void aRejectedActionPublishesNothingBecauseTheEventIsInTheSameTransaction() throws Exception {
        int before = kafkaPayloads().size();

        // a USER may not change roles, a non-ADMIN may not change the theme: nothing happened, nothing is journaled
        changeRole(userId, adminId, "USER").andExpect(status().isForbidden());
        mockMvc.perform(put("/organizations/{t}/theme", tenantId)
                        .header("X-User-Id", userId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"paletteId\":\"forest\"}"))
                .andExpect(status().isForbidden());

        assertThat(kafkaPayloads()).hasSize(before);
    }

    @Test
    @Tag("REQ-MON-01")
    void rolesAndThemeChangesPublishOnlyEventTypesTheJournalKnows() throws Exception {
        changeRole(adminId, userId, "MANAGER").andExpect(status().isOk());
        mockMvc.perform(put("/organizations/{t}/theme", tenantId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"paletteId\":\"forest\"}"))
                .andExpect(status().isOk());

        List<String> types = typesOnKafka();

        assertThat(types).isNotEmpty().allSatisfy(type -> assertThat(ActivityEventCatalog.find(type))
                .as("%s is published to Kafka but not in the journal catalog", type).isPresent());
    }

    /** The newest outbox row of this type for the tenant, checked against the catalog (the contract). */
    private JsonNode publishedPayload(String eventType, UUID expectedActor) throws Exception {
        Map<String, Object> row = query("SELECT destination, channel, payload FROM org.outbox WHERE tenant_id = ? AND event_type = ?"
                + " ORDER BY created_at DESC LIMIT 1", tenantId, eventType);
        assertThat(row.get("destination")).isEqualTo("KAFKA");
        assertThat(row.get("channel")).isEqualTo("org.events");
        JsonNode envelope = objectMapper.readTree((String) row.get("payload"));
        assertThat(envelope.get("tenantId").asText()).isEqualTo(tenantId.toString());
        JsonNode payload = envelope.get("payload");

        var entry = ActivityEventCatalog.find(eventType).orElseThrow();
        if (entry.actorField() == null) {
            assertThat(expectedActor).as("a system action has no actor").isNull();
        } else {
            assertThat(payload.get(entry.actorField()).asText()).as("actor (%s)", entry.actorField()).isEqualTo(expectedActor.toString());
        }
        for (String field : entry.metadataFields()) {
            assertThat(payload.has(field) && !payload.get(field).isNull()).as("%s carries %s", eventType, field).isTrue();
        }
        assertThat(payload.toString()).as("no personal data in a Kafka payload").doesNotContain("@");
        return payload;
    }

    private List<String> kafkaPayloads() throws Exception {
        return queryList("SELECT payload FROM org.outbox WHERE tenant_id = ? AND destination = 'KAFKA'", tenantId).stream()
                .map(row -> (String) row.get("payload")).toList();
    }

    private List<String> typesOnKafka() throws Exception {
        return queryList("SELECT event_type FROM org.outbox WHERE tenant_id = ? AND destination = 'KAFKA'", tenantId).stream()
                .map(row -> (String) row.get("event_type")).toList();
    }

    private ResultActions changeRole(UUID caller, UUID target, String role) throws Exception {
        return mockMvc.perform(patch("/organizations/{t}/members/{u}/role", tenantId, target)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenantId.toString())
                .contentType("application/json").content("{\"role\":\"" + role + "\"}"));
    }

    private UUID createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .header("X-User-Id", creatorUserId.toString()).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private void insertMembership(UUID user, String role) throws Exception {
        update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, false)",
                UUID.randomUUID(), tenantId, user, role);
    }

    private Map<String, Object> query(String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    private List<Map<String, Object>> queryList(String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            return jdbc.queryForList(sql, args);
        }
    }

    private void update(String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false)");
            jdbc.update(sql, args);
        }
    }

    /** Reads a UUID field from a MockMvc JSON response body. */
    private final class MvcResultHolder {
        private final JsonNode body;

        MvcResultHolder(ResultActions actions) throws Exception {
            this.body = objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }

        UUID uuid(String field) {
            return UUID.fromString(body.get(field).asText());
        }
    }
}
