package com.saas.org;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.cache.MembershipAccessCache;
import com.saas.org.cache.MembershipAccessCache.MemberAccess;
import com.saas.org.client.TenantAccessStatusClient;
import com.saas.org.event.SubscriptionChangedPayload;
import com.saas.org.events.SubscriptionChangedListener;
import com.saas.shared.events.EventEnvelope;
import com.saas.testsupport.containers.RedisTestContainer;
import com.saas.testsupport.containers.SharedContainers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Redis cache (REQ-SCALE-01) against a real Redis and a real Postgres: the second read of the theme, of a
 * member's role and of billing's write-access answer comes from the cache, and each is replaced as soon as what
 * changes it happens - the theme and the logo by the ADMIN, the role by a role change, an accepted demotion or an
 * offboarding, the access status by billing's subscription-changed event. The rest of the suite runs with the cache
 * off ({@code src/test/resources/application.properties}); this class turns it on. billing-service is a stub that
 * counts how often it is asked.
 */
@SpringBootTest(properties = {
        "saas.cache.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=RedisCacheIT"
})
@AutoConfigureMockMvc
class RedisCacheIT implements RedisTestContainer {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final JdbcTemplate admin = new JdbcTemplate(DataSourceBuilder.create()
            .url(System.getProperty("spring.datasource.url")).username("admin").password("admin").build());
    private static final AtomicInteger billingCalls = new AtomicInteger();
    private static volatile int billingHttpStatus = 200;
    private static volatile String accessStatusResponse = "{\"status\":\"ACTIVE\"}";
    private static HttpServer billingStub = startBillingStub();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MembershipAccessCache memberAccess;
    @Autowired
    private TenantAccessStatusClient accessStatus;
    @Autowired
    private SubscriptionChangedListener subscriptionChangedListener;

    private UUID tenantId;
    private UUID adminId;
    private UUID userId;

    private static Path createStorageRoot() {
        try {
            Path dir = Files.createTempDirectory("msaas-cache-it");
            System.setProperty("saas.storage.local.root", dir.toString());
            return dir;
        } catch (Exception e) {
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
                    + " ('forest', 'Forest', 'COLOR', '{\"primary\":\"#15803D\",\"secondary\":\"#3F3F46\",\"accent\":\"#EAB308\"}'),"
                    + " ('ocean', 'Ocean', 'COLOR', '{\"primary\":\"#0369A1\",\"secondary\":\"#134E4A\",\"accent\":\"#F472B6\"}')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return container;
    }

    private static HttpServer startBillingStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/tenants/", exchange -> {
                billingCalls.incrementAndGet();
                byte[] body = accessStatusResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(billingHttpStatus, body.length);
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
    void aTenantWithAnAdminAndAUser() throws Exception {
        billingHttpStatus = 200;
        accessStatusResponse = "{\"status\":\"ACTIVE\"}";
        adminId = UUID.randomUUID();
        tenantId = createOrganization("Cache Co", adminId);
        userId = UUID.randomUUID();
        insertMembership(tenantId, userId, "USER");
    }

    // ---- theme ----

    @Test
    @Tag("REQ-SCALE-01")
    void aSecondThemeReadComesFromRedisAndAnAdminsChangeReplacesIt() throws Exception {
        getTheme(tenantId, userId).andExpect(status().isOk()).andExpect(jsonPath("$.paletteId").value("default"));
        assertThat(redis.hasKey(themeKey(tenantId))).isTrue();

        // changed behind the service's back: only a cache can still answer "default"
        admin.update("UPDATE org.tenant_settings SET theme_json = jsonb_set(theme_json, '{paletteId}', '\"forest\"') WHERE tenant_id = ?", tenantId);
        getTheme(tenantId, userId).andExpect(jsonPath("$.paletteId").value("default"));

        putTheme(tenantId, adminId, "{\"paletteId\":\"ocean\"}").andExpect(status().isOk()).andExpect(jsonPath("$.paletteId").value("ocean"));
        getTheme(tenantId, userId).andExpect(jsonPath("$.paletteId").value("ocean"));
    }

    @Test
    @Tag("REQ-SCALE-01")
    void aNewLogoReplacesTheCachedTheme() throws Exception {
        getTheme(tenantId, userId).andExpect(jsonPath("$.logoUrl").doesNotExist());
        assertThat(redis.hasKey(themeKey(tenantId))).isTrue();

        mockMvc.perform(multipart("/organizations/{tenantId}/theme/logo", tenantId)
                        .file(new MockMultipartFile("file", "logo.png", "image/png", TestImages.image("png", 32, 32)))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());

        getTheme(tenantId, userId).andExpect(jsonPath("$.logoUrl").value(org.hamcrest.Matchers.startsWith("/organizations/" + tenantId + "/theme/logo?v=")));
    }

    @Test
    @Tag("REQ-SCALE-01")
    @Tag("REQ-DATA-03")
    void aCachedThemeNeverReachesAnOutsiderAndEachTenantHasItsOwnEntry() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenant = createOrganization("Other Co", otherAdmin);
        putTheme(tenantId, adminId, "{\"paletteId\":\"forest\"}").andExpect(status().isOk());
        getTheme(tenantId, adminId).andExpect(status().isOk());
        getTheme(otherTenant, otherAdmin).andExpect(jsonPath("$.paletteId").value("default"));

        getTheme(tenantId, otherAdmin).andExpect(status().isForbidden());
        getTheme(tenantId, UUID.randomUUID()).andExpect(status().isForbidden());

        assertThat(redis.hasKey(themeKey(tenantId))).isTrue();
        assertThat(redis.hasKey(themeKey(otherTenant))).isTrue();
        getTheme(tenantId, adminId).andExpect(jsonPath("$.paletteId").value("forest"));
        getTheme(otherTenant, otherAdmin).andExpect(jsonPath("$.paletteId").value("default"));
    }

    // ---- roles ----

    @Test
    @Tag("REQ-SCALE-01")
    void aSecondRoleReadComesFromRedisAndARoleChangeReplacesIt() throws Exception {
        assertThat(memberAccess.find(tenantId, userId)).contains(new MemberAccess("USER", false));

        admin.update("UPDATE org.memberships SET role = 'MANAGER' WHERE tenant_id = ? AND user_id = ?", tenantId, userId);
        assertThat(memberAccess.find(tenantId, userId)).contains(new MemberAccess("USER", false));

        patchRole(tenantId, adminId, userId, "ADMIN").andExpect(status().isOk());
        assertThat(memberAccess.find(tenantId, userId)).contains(new MemberAccess("ADMIN", false));
    }

    @Test
    @Tag("REQ-SCALE-01")
    void aDemotionThatTheAdminAcceptsReplacesTheirCachedRole() throws Exception {
        UUID secondAdmin = UUID.randomUUID();
        insertMembership(tenantId, secondAdmin, "ADMIN");
        assertThat(memberAccess.find(tenantId, secondAdmin)).contains(new MemberAccess("ADMIN", false));

        patchRole(tenantId, adminId, secondAdmin, "USER").andExpect(status().isAccepted());
        assertThat(memberAccess.find(tenantId, secondAdmin)).contains(new MemberAccess("ADMIN", false));
        UUID requestId = admin.queryForObject("SELECT id FROM org.admin_demotion_requests WHERE tenant_id = ?", UUID.class, tenantId);

        mockMvc.perform(post("/organizations/{t}/admin-demotion-requests/{r}/accept", tenantId, requestId)
                        .header("X-User-Id", secondAdmin.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk());

        assertThat(memberAccess.find(tenantId, secondAdmin)).contains(new MemberAccess("USER", false));
    }

    @Test
    @Tag("REQ-SCALE-01")
    void aMemberTakenOffTheTenantLosesTheirCachedAccessAtOnce() throws Exception {
        getTheme(tenantId, userId).andExpect(status().isOk());
        assertThat(memberAccess.find(tenantId, userId)).isPresent();

        mockMvc.perform(delete("/organizations/{t}/members/{u}", tenantId, userId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isNoContent());

        assertThat(memberAccess.find(tenantId, userId)).isEmpty();
        getTheme(tenantId, userId).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-SCALE-01")
    void notBeingAMemberIsNeverRemembered() throws Exception {
        UUID newcomer = UUID.randomUUID();
        assertThat(memberAccess.find(tenantId, newcomer)).isEmpty();
        assertThat(redis.hasKey(roleKey(tenantId, newcomer))).isFalse();

        insertMembership(tenantId, newcomer, "USER");

        assertThat(memberAccess.find(tenantId, newcomer)).contains(new MemberAccess("USER", false));
    }

    @Test
    @Tag("REQ-SCALE-01")
    @Tag("REQ-DATA-03")
    void theSameUserHasASeparateCachedRoleInEveryTenant() throws Exception {
        UUID otherTenant = createOrganization("Other Co", userId); // the user is an ADMIN there

        assertThat(memberAccess.find(tenantId, userId)).contains(new MemberAccess("USER", false));
        assertThat(memberAccess.find(otherTenant, userId)).contains(new MemberAccess("ADMIN", false));
        assertThat(roleKey(tenantId, userId)).isNotEqualTo(roleKey(otherTenant, userId));
        assertThat(redis.hasKey(roleKey(tenantId, userId))).isTrue();
        assertThat(redis.hasKey(roleKey(otherTenant, userId))).isTrue();
    }

    // ---- subscription / write access ----

    @Test
    @Tag("REQ-SCALE-01")
    void aSecondAccessStatusReadComesFromRedisAndTheSubscriptionEventReplacesIt() throws Exception {
        int before = billingCalls.get();
        assertThat(accessStatus.accessStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(billingCalls.get()).isEqualTo(before + 1);

        accessStatusResponse = "{\"status\":\"SUSPENDED\"}"; // billing changed its mind; nothing has said so yet
        assertThat(accessStatus.accessStatus(tenantId)).isEqualTo("ACTIVE");
        assertThat(billingCalls.get()).as("the second read did not ask billing").isEqualTo(before + 1);
        putTheme(tenantId, adminId, "{\"paletteId\":\"forest\"}").andExpect(status().isOk());

        subscriptionChangedListener.onBillingEvent(subscriptionChanged(tenantId, "SUSPENDED"));

        assertThat(accessStatus.accessStatus(tenantId)).isEqualTo("SUSPENDED");
        assertThat(billingCalls.get()).isGreaterThan(before + 1);
        putTheme(tenantId, adminId, "{\"paletteId\":\"ocean\"}").andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-SCALE-01")
    @Tag("REQ-DATA-03")
    void aSubscriptionEventOnlyReplacesThatTenantsAccessStatus() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenant = createOrganization("Other Co", otherAdmin);
        accessStatus.accessStatus(tenantId);
        accessStatus.accessStatus(otherTenant);

        subscriptionChangedListener.onBillingEvent(subscriptionChanged(tenantId, "READ_ONLY"));

        assertThat(redis.hasKey(accessKey(tenantId))).isFalse();
        assertThat(redis.hasKey(accessKey(otherTenant))).isTrue();
    }

    @Test
    @Tag("REQ-SCALE-01")
    void anAnswerGivenBecauseBillingCouldNotBeReachedIsNeverCached() throws Exception {
        billingHttpStatus = 500;
        assertThat(accessStatus.accessStatus(tenantId)).as("fails open").isEqualTo("ACTIVE");
        assertThat(redis.hasKey(accessKey(tenantId))).isFalse();

        billingHttpStatus = 200;
        accessStatusResponse = "{\"status\":\"READ_ONLY\"}";
        assertThat(accessStatus.accessStatus(tenantId)).isEqualTo("READ_ONLY");
    }

    @Test
    @Tag("REQ-SCALE-01")
    void everyEntryExpiresOnItsOwnSoAMissedRemovalHealsItself() throws Exception {
        getTheme(tenantId, userId).andExpect(status().isOk());
        accessStatus.accessStatus(tenantId);

        assertThat(redis.getExpire(themeKey(tenantId), TimeUnit.SECONDS)).isBetween(1L, 300L);
        assertThat(redis.getExpire(roleKey(tenantId, userId), TimeUnit.SECONDS)).isBetween(1L, 300L);
        assertThat(redis.getExpire(accessKey(tenantId), TimeUnit.SECONDS)).isBetween(1L, 60L);
    }

    @Test
    @Tag("REQ-SCALE-01")
    @Tag("REQ-AUTH-01")
    void theTokenIsIssuedFromTheMembershipsThemselvesNotFromTheRoleCache() throws Exception {
        assertThat(memberAccess.find(tenantId, userId)).contains(new MemberAccess("USER", false));
        admin.update("UPDATE org.memberships SET role = 'MANAGER' WHERE tenant_id = ? AND user_id = ?", tenantId, userId);

        mockMvc.perform(get("/internal/users/{u}/memberships", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("MANAGER"));
    }

    // ---- helpers ----

    private static String themeKey(UUID tenant) {
        return "org:cache:theme:" + tenant;
    }

    private static String roleKey(UUID tenant, UUID user) {
        return "org:cache:member-access:" + tenant + ":" + user;
    }

    private static String accessKey(UUID tenant) {
        return "org:cache:access-status:" + tenant;
    }

    private String subscriptionChanged(UUID tenant, String status) throws Exception {
        return objectMapper.writeValueAsString(EventEnvelope.forTenant("SUBSCRIPTION_CHANGED", 1, tenant,
                new SubscriptionChangedPayload(tenant, UUID.randomUUID(), 10, status, null, "ACTIVE")));
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

    private ResultActions patchRole(UUID tenant, UUID caller, UUID target, String role) throws Exception {
        return mockMvc.perform(patch("/organizations/{t}/members/{u}/role", tenant, target)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString())
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

    private void insertMembership(UUID tenant, UUID user, String role) {
        admin.update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, false)",
                UUID.randomUUID(), tenant, user, role);
    }
}
