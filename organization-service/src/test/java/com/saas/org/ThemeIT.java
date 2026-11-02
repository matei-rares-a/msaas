package com.saas.org;

import com.saas.org.entity.Membership;
import com.saas.testsupport.containers.SharedContainers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A tenant's theme (REQ-PERS-01, REQ-PERS-02, REQ-PERS-04, REQ-PERS-05, REQ-PERS-06): the default one at
 * creation, ADMIN-only changes, palette ids from the global catalog and nothing else, read through a
 * dedicated endpoint. The catalog table belongs to auth-service, so it is created inline here, same as
 * billing-service's tests do for {@code global.plans}. billing-service is stubbed like in
 * {@link TenantAccessGuardIT}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=ThemeIT"
})
@AutoConfigureMockMvc
class ThemeIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String accessStatusResponse = "{\"status\":\"ACTIVE\"}";
    private static HttpServer billingStub = startBillingStub();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;

    private UUID tenantId;
    private UUID adminId;
    private UUID managerId;
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

        // auth-service's Flyway owns global.palettes; here the same shape is created inline with a few rows
        try (var connection = java.sql.DriverManager.getConnection(globalUrl, "admin", "admin");
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS global.palettes (id VARCHAR(50) PRIMARY KEY, name VARCHAR(100) NOT NULL,"
                    + " kind VARCHAR(20) NOT NULL, definition JSONB NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE)");
            statement.execute("GRANT SELECT ON global.palettes TO org_svc");
            statement.execute("INSERT INTO global.palettes (id, name, kind, definition) VALUES"
                    + " ('default', 'Classic blue', 'COLOR', '{\"primary\":\"#1D4ED8\",\"secondary\":\"#475569\",\"accent\":\"#F59E0B\"}'),"
                    + " ('forest', 'Forest', 'COLOR', '{\"primary\":\"#15803D\",\"secondary\":\"#3F3F46\",\"accent\":\"#EAB308\"}'),"
                    + " ('ocean', 'Ocean', 'GRADIENT', '{\"primary\":\"#0369A1\",\"secondary\":\"#134E4A\",\"accent\":\"#F472B6\","
                    + "\"gradient\":[\"#0EA5E9\",\"#14B8A6\"]}')");
            statement.execute("INSERT INTO global.palettes (id, name, kind, definition, active) VALUES"
                    + " ('retired', 'Retired', 'COLOR', '{\"primary\":\"#000000\",\"secondary\":\"#111111\",\"accent\":\"#222222\"}', false)");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return container;
    }

    private static HttpServer startBillingStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/tenants/", exchange -> {
                byte[] body = accessStatusResponse.getBytes(StandardCharsets.UTF_8);
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
    void setUpTenantWithOneMemberPerRole() throws Exception {
        accessStatusResponse = "{\"status\":\"ACTIVE\"}";
        adminId = UUID.randomUUID();
        tenantId = createOrganization("Theme Co", adminId);
        managerId = UUID.randomUUID();
        insertMembership(tenantId, managerId, "MANAGER", false);
        userId = UUID.randomUUID();
        insertMembership(tenantId, userId, "USER", false);
    }

    @Test
    @Tag("REQ-PERS-05")
    void aNewTenantStartsWithTheDefaultThemeWithoutAnyoneSettingIt() throws Exception {
        Map<String, Object> row = query(tenantId, "SELECT theme_json->>'paletteId' AS palette_id FROM org.tenant_settings WHERE tenant_id = ?", tenantId);
        assertThat(row.get("palette_id")).isEqualTo("default");

        getTheme(tenantId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paletteId").value("default"))
                .andExpect(jsonPath("$.palette.kind").value("COLOR"))
                .andExpect(jsonPath("$.palette.definition.primary").value("#1D4ED8"));
    }

    @Test
    @Tag("REQ-PERS-04")
    void settingsAreAFlexibleJsonDocumentAndAThemeChangeKeepsTheOtherKeys() throws Exception {
        assertThat(query(tenantId, "SELECT data_type FROM information_schema.columns WHERE table_schema = 'org'"
                + " AND table_name = 'tenant_settings' AND column_name = 'theme_json'").get("data_type")).isEqualTo("jsonb");
        // a key this step does not know about (the logo arrives in P8.2, flags in P8.3) must survive a theme change
        update(tenantId, "UPDATE org.tenant_settings SET theme_json = theme_json || '{\"logoUrl\":\"https://cdn.example/logo.png\"}'::jsonb WHERE tenant_id = ?", tenantId);

        putTheme(tenantId, adminId, "{\"paletteId\":\"forest\"}").andExpect(status().isOk());

        Map<String, Object> stored = query(tenantId, "SELECT theme_json->>'paletteId' AS p, theme_json->>'logoUrl' AS l FROM org.tenant_settings WHERE tenant_id = ?", tenantId);
        assertThat(stored.get("p")).isEqualTo("forest");
        assertThat(stored.get("l")).isEqualTo("https://cdn.example/logo.png");
    }

    @Test
    @Tag("REQ-PERS-06")
    void aChangeIsReadBackThroughTheDedicatedEndpointWithNoNewToken() throws Exception {
        putTheme(tenantId, adminId, "{\"paletteId\":\"ocean\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paletteId").value("ocean"));

        getTheme(tenantId, managerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paletteId").value("ocean"))
                .andExpect(jsonPath("$.palette.kind").value("GRADIENT"))
                .andExpect(jsonPath("$.palette.definition.gradient.length()").value(2));
    }

    @Test
    @Tag("REQ-PERS-06")
    void thePaletteListOffersOnlyActiveCatalogPalettes() throws Exception {
        mockMvc.perform(get("/organizations/{tenantId}/theme/palettes", tenantId)
                        .header("X-User-Id", userId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'forest')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'retired')]").doesNotExist());
    }

    @ParameterizedTest(name = "{0} is rejected as a palette")
    @ValueSource(strings = {"does-not-exist", "retired", "#ff0000", "red;background:url(https://evil.example/x)",
            "rgb(0,0,0)", "</style><script>alert(1)</script>", "FOREST", "forest ", "a-very-long-id-that-goes-on-and-on-and-on-and-on-and-on-and-on"})
    @Tag("REQ-PERS-02")
    void anythingThatIsNotAnActiveCatalogPaletteIdIsRejectedAndNothingChanges(String paletteId) throws Exception {
        putTheme(tenantId, adminId, "{\"paletteId\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(paletteId) + "}")
                .andExpect(status().isBadRequest());

        assertThat(query(tenantId, "SELECT theme_json->>'paletteId' AS p FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("p"))
                .isEqualTo("default");
    }

    @ParameterizedTest(name = "body {0} is rejected")
    @ValueSource(strings = {"{\"paletteId\":\"forest\",\"primaryColor\":\"#ff0000\"}", "{\"primary\":\"#ff0000\"}",
            "{\"paletteId\":\"forest\",\"css\":\"body{display:none}\"}", "{}", "{\"paletteId\":null}", "not json"})
    @Tag("REQ-PERS-02")
    void aRequestThatCarriesAnythingBesidesAPaletteIdIsRejected(String body) throws Exception {
        putTheme(tenantId, adminId, body).andExpect(status().isBadRequest());

        assertThat(query(tenantId, "SELECT theme_json->>'paletteId' AS p FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("p"))
                .isEqualTo("default");
    }

    @ParameterizedTest(name = "{0} changing the theme")
    @EnumSource(value = Membership.Role.class, names = {"MANAGER", "USER"})
    @Tag("REQ-PERS-01")
    void onlyAnAdminCanChangeTheTheme(Membership.Role role) throws Exception {
        UUID caller = role == Membership.Role.MANAGER ? managerId : userId;

        putTheme(tenantId, caller, "{\"paletteId\":\"forest\"}").andExpect(status().isForbidden());

        assertThat(query(tenantId, "SELECT theme_json->>'paletteId' AS p FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("p"))
                .isEqualTo("default");
    }

    @Test
    @Tag("REQ-PERS-01")
    void anAdminWithoutWriteAccessCannotChangeTheTheme() throws Exception {
        UUID readOnlyAdmin = UUID.randomUUID();
        insertMembership(tenantId, readOnlyAdmin, "ADMIN", true);

        putTheme(tenantId, readOnlyAdmin, "{\"paletteId\":\"forest\"}").andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-PERS-01")
    void everyMemberCanReadTheThemeButANonMemberCannot() throws Exception {
        for (UUID member : List.of(adminId, managerId, userId)) {
            getTheme(tenantId, member).andExpect(status().isOk());
        }
        getTheme(tenantId, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-AUTH-04")
    void aTokenForAnotherTenantCannotReadOrChangeTheTheme() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);

        mockMvc.perform(get("/organizations/{tenantId}/theme", tenantId)
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/organizations/{tenantId}/theme", tenantId)
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString())
                        .contentType("application/json").content("{\"paletteId\":\"forest\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-DATA-03")
    void eachTenantHasItsOwnThemeAndOneTenantsChangeNeverShowsInAnother() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);

        putTheme(tenantId, adminId, "{\"paletteId\":\"ocean\"}").andExpect(status().isOk());

        mockMvc.perform(get("/organizations/{tenantId}/theme", otherTenantId)
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paletteId").value("default"));
    }

    @Test
    @Tag("REQ-BILL-04")
    void aTenantInTheGracePeriodCannotChangeItsTheme() throws Exception {
        accessStatusResponse = "{\"status\":\"READ_ONLY\"}";

        putTheme(tenantId, adminId, "{\"paletteId\":\"forest\"}").andExpect(status().isForbidden());

        getTheme(tenantId, adminId).andExpect(status().isOk());
    }

    private ResultActions getTheme(UUID tenant, UUID caller) throws Exception {
        return mockMvc.perform(get("/organizations/{tenantId}/theme", tenant)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString()));
    }

    private ResultActions putTheme(UUID tenant, UUID caller, String body) throws Exception {
        return mockMvc.perform(put("/organizations/{tenantId}/theme", tenant)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString())
                .contentType("application/json").content(body));
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

    private void insertMembership(UUID tenant, UUID user, String role, boolean readOnly) throws Exception {
        update(tenant, "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, user, role, readOnly);
    }

    private Map<String, Object> query(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }

    private Map<String, Object> query(String sql) throws Exception {
        return query(tenantId, sql);
    }

    private void update(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            jdbc.update(sql, args);
        }
    }
}
