package com.saas.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.service.FeatureFlagService;
import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature flags per tenant (REQ-PERS-07, REQ-ARCH-02): disabled by default, switched per tenant, never
 * visible across tenants, stored in the tenant's settings document, and not something a tenant can switch
 * itself. Same dedicated-container setup as {@link ThemeIT}.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "saas.feature-flags.known=beta-dashboard,new-reports",
        "test.marker=FeatureFlagIT"
})
@AutoConfigureMockMvc
class FeatureFlagIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private FeatureFlagService featureFlagService;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
        return container;
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUpTenant() throws Exception {
        adminId = UUID.randomUUID();
        tenantId = createOrganization("Flag Co", adminId);
        userId = UUID.randomUUID();
        update(tenantId, "INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, 'USER', false)",
                UUID.randomUUID(), tenantId, userId);
    }

    @Test
    @Tag("REQ-PERS-07")
    void everyKnownFlagIsDisabledForANewTenant() throws Exception {
        readFlags(tenantId, userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.beta-dashboard").value(false))
                .andExpect(jsonPath("$.new-reports").value(false));
        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isFalse();
        assertThat(featureFlagService.isEnabled(tenantId, "new-reports")).isFalse();
    }

    @Test
    @Tag("REQ-PERS-07")
    void aFlagSwitchedOnForOneTenantDoesNotAffectAnother() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);

        setFlag(tenantId, "beta-dashboard", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beta-dashboard").value(true))
                .andExpect(jsonPath("$.new-reports").value(false));

        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isTrue();
        assertThat(featureFlagService.isEnabled(otherTenantId, "beta-dashboard")).isFalse();
        readFlags(otherTenantId, otherAdmin).andExpect(jsonPath("$.beta-dashboard").value(false));
        readFlags(tenantId, userId).andExpect(jsonPath("$.beta-dashboard").value(true));
    }

    @Test
    @Tag("REQ-PERS-07")
    void aFlagCanBeSwitchedBackOffAndOtherFlagsAreKept() throws Exception {
        setFlag(tenantId, "beta-dashboard", true).andExpect(status().isOk());
        setFlag(tenantId, "new-reports", true).andExpect(status().isOk());

        setFlag(tenantId, "beta-dashboard", false).andExpect(status().isOk())
                .andExpect(jsonPath("$.beta-dashboard").value(false))
                .andExpect(jsonPath("$.new-reports").value(true));

        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isFalse();
        assertThat(featureFlagService.isEnabled(tenantId, "new-reports")).isTrue();
    }

    @Test
    @Tag("REQ-ARCH-02")
    void flagsAreBooleansInTheTenantSettingsDocumentNextToTheTheme() throws Exception {
        setFlag(tenantId, "beta-dashboard", true).andExpect(status().isOk());

        Map<String, Object> row = query(tenantId, "SELECT feature_flags_json::text AS flags, theme_json->>'paletteId' AS palette,"
                + " (SELECT data_type FROM information_schema.columns WHERE table_schema = 'org' AND table_name = 'tenant_settings'"
                + " AND column_name = 'feature_flags_json') AS type FROM org.tenant_settings WHERE tenant_id = ?", tenantId);
        assertThat(row.get("type")).isEqualTo("jsonb");
        assertThat((String) row.get("flags")).contains("\"beta-dashboard\": true");
        assertThat(row.get("palette")).isEqualTo("default"); // the theme is untouched by a flag change
        assertThat(query(tenantId, "SELECT count(*) AS n FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("n")).isEqualTo(1L);
    }

    @Test
    @Tag("REQ-ARCH-02")
    void aFlagChangeNeverTouchesTheThemeAndAThemeChangeNeverTouchesTheFlags() throws Exception {
        update(tenantId, "UPDATE org.tenant_settings SET theme_json = theme_json || '{\"logoKey\":\"tenants/x/logo-1.png\"}'::jsonb WHERE tenant_id = ?", tenantId);

        setFlag(tenantId, "new-reports", true).andExpect(status().isOk());

        assertThat((String) query(tenantId, "SELECT theme_json->>'logoKey' AS k FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("k"))
                .isEqualTo("tenants/x/logo-1.png");
    }

    @Test
    @Tag("REQ-ARCH-02")
    void aTenantWithoutASettingsRowStillGetsItsFlagAndAdefaultTheme() throws Exception {
        update(tenantId, "DELETE FROM org.tenant_settings WHERE tenant_id = ?", tenantId);
        readFlags(tenantId, userId).andExpect(status().isOk()).andExpect(jsonPath("$.beta-dashboard").value(false));

        setFlag(tenantId, "beta-dashboard", true).andExpect(status().isOk());

        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isTrue();
        assertThat(query(tenantId, "SELECT theme_json->>'paletteId' AS p FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("p"))
                .isEqualTo("default");
    }

    @ParameterizedTest(name = "flag {0} is rejected")
    @ValueSource(strings = {"does-not-exist", "BETA-DASHBOARD", "beta_dashboard", "beta-dashboard ", "x'; DROP TABLE org.tenants;--",
            "{\"a\":1}", "..", "a-very-long-flag-name-that-goes-on-and-on-and-on-and-on-and-on-and-on-and-on"})
    @Tag("REQ-PERS-07")
    void onlyDeclaredFlagNamesCanBeSet(String flag) throws Exception {
        mockMvc.perform(put("/internal/tenants/{tenantId}/feature-flags/{flag}", tenantId, flag)
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().is4xxClientError());

        assertThat((String) query(tenantId, "SELECT feature_flags_json::text AS f FROM org.tenant_settings WHERE tenant_id = ?", tenantId).get("f"))
                .isEqualTo("{}");
    }

    @ParameterizedTest(name = "body {0} is rejected")
    @ValueSource(strings = {"{}", "{\"enabled\":null}", "not json", "{\"enabled\":\"yes\"}", "[true]"})
    @Tag("REQ-PERS-07")
    void theValueMustBeABoolean(String body) throws Exception {
        mockMvc.perform(put("/internal/tenants/{tenantId}/feature-flags/{flag}", tenantId, "beta-dashboard")
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isFalse();
    }

    @Test
    @Tag("REQ-PERS-07")
    void anUnknownTenantIsRejected() throws Exception {
        setFlag(UUID.randomUUID(), "beta-dashboard", true).andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PERS-07")
    void aFlagThatIsNotDeclaredIsNeverEnabledEvenIfItIsInTheStoredDocument() throws Exception {
        update(tenantId, "UPDATE org.tenant_settings SET feature_flags_json = '{\"leftover\":true,\"beta-dashboard\":true}'::jsonb WHERE tenant_id = ?", tenantId);

        assertThat(featureFlagService.isEnabled(tenantId, "leftover")).isFalse();
        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isTrue();
        readFlags(tenantId, userId).andExpect(jsonPath("$.leftover").doesNotExist());
    }

    @Test
    @Tag("REQ-PERS-07")
    void aTenantCannotSwitchItsOwnFlagsThroughAnyTenantFacingRoute() throws Exception {
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .filteredOn(info -> info.getPatternValues().stream().anyMatch(p -> p.contains("feature-flags") && p.startsWith("/organizations")))
                .allSatisfy(info -> assertThat(info.getMethodsCondition().getMethods()).containsOnly(RequestMethod.GET));

        // an ADMIN of the tenant trying anyway finds no such route
        mockMvc.perform(put("/organizations/{tenantId}/feature-flags/{flag}", tenantId, "beta-dashboard")
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(status().is4xxClientError());
        assertThat(featureFlagService.isEnabled(tenantId, "beta-dashboard")).isFalse();
    }

    @Test
    @Tag("REQ-PERS-07")
    void everyMemberReadsTheirTenantsFlagsButANonMemberCannot() throws Exception {
        readFlags(tenantId, adminId).andExpect(status().isOk());
        readFlags(tenantId, userId).andExpect(status().isOk());
        readFlags(tenantId, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-AUTH-04")
    void aTokenForAnotherTenantCannotReadThisTenantsFlags() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Intruder Co", otherAdmin);
        setFlag(tenantId, "beta-dashboard", true).andExpect(status().isOk());

        mockMvc.perform(get("/organizations/{tenantId}/feature-flags", tenantId)
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isForbidden());
    }

    private ResultActions readFlags(UUID tenant, UUID caller) throws Exception {
        return mockMvc.perform(get("/organizations/{tenantId}/feature-flags", tenant)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString()));
    }

    private ResultActions setFlag(UUID tenant, String flag, boolean enabled) throws Exception {
        return mockMvc.perform(put("/internal/tenants/{tenantId}/feature-flags/{flag}", tenant, flag)
                .contentType("application/json").content("{\"enabled\":" + enabled + "}"));
    }

    private UUID createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .header("X-User-Id", creatorUserId.toString()).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Map body = new ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }

    private Map<String, Object> query(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            return jdbc.queryForMap(sql, args);
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
