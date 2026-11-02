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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant logo over HTTP (REQ-PERS-01, REQ-PERS-03): ADMIN-only, PNG/JPEG decided by content, size and
 * pixel limits, stored under a per-tenant key, served back with a fixed type. Same dedicated-container
 * setup as {@link ThemeIT}; the local object storage points at a temporary directory.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=LogoIT"
})
@AutoConfigureMockMvc
class LogoIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static final Path STORAGE_ROOT = createStorageRoot();
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

    private static Path createStorageRoot() {
        try {
            Path dir = Files.createTempDirectory("msaas-logo-it");
            System.setProperty("saas.storage.local.root", dir.toString());
            return dir;
        } catch (IOException e) {
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
                    + " ('default', 'Classic blue', 'COLOR', '{\"primary\":\"#1D4ED8\",\"secondary\":\"#475569\",\"accent\":\"#F59E0B\"}')");
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
        tenantId = createOrganization("Logo Co", adminId);
        managerId = UUID.randomUUID();
        insertMembership(tenantId, managerId, "MANAGER", false);
        userId = UUID.randomUUID();
        insertMembership(tenantId, userId, "USER", false);
    }

    @Test
    @Tag("REQ-PERS-03")
    void aValidPngIsStoredUnderAKeyOfThisTenantAndAppearsInTheTheme() throws Exception {
        byte[] png = TestImages.image("png", 64, 64);

        MvcResult result = upload(tenantId, adminId, "logo.png", "image/png", png).andExpect(status().isOk()).andReturn();

        String logoUrl = (String) new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(), Map.class).get("logoUrl");
        assertThat(logoUrl).startsWith("/organizations/" + tenantId + "/theme/logo?v=");
        String key = storedKey(tenantId);
        assertThat(key).startsWith("tenants/" + tenantId + "/").endsWith(".png");
        assertThat(Files.readAllBytes(STORAGE_ROOT.resolve(key))).isEqualTo(png);
        mockMvc.perform(get("/organizations/{tenantId}/theme", tenantId)
                        .header("X-User-Id", userId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").value(logoUrl))
                .andExpect(jsonPath("$.paletteId").value("default"));
    }

    @Test
    @Tag("REQ-PERS-03")
    void aValidJpegIsAccepted() throws Exception {
        upload(tenantId, adminId, "logo.jpg", "image/jpeg", TestImages.image("jpg", 128, 64)).andExpect(status().isOk());

        assertThat(storedKey(tenantId)).endsWith(".jpg");
    }

    @Test
    @Tag("REQ-PERS-03")
    void everyMemberReadsTheLogoBackWithTheValidatedTypeAndNoSniffing() throws Exception {
        byte[] png = TestImages.image("png", 32, 32);
        upload(tenantId, adminId, "logo.png", "image/png", png).andExpect(status().isOk());

        for (UUID member : List.of(adminId, managerId, userId)) {
            MvcResult result = download(tenantId, member).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "image/png"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(png);
        }
    }

    @Test
    @Tag("REQ-PERS-03")
    void theTypeComesFromTheBytesNotFromTheNameOrTheDeclaredContentType() throws Exception {
        // a JPEG called logo.png and declared as image/png is stored and served as the JPEG it is
        upload(tenantId, adminId, "logo.png", "image/png", TestImages.image("jpg", 32, 32)).andExpect(status().isOk());

        assertThat(storedKey(tenantId)).endsWith(".jpg");
        download(tenantId, userId).andExpect(header().string("Content-Type", "image/jpeg"));
    }

    @Test
    @Tag("REQ-PERS-03")
    void anSvgIsRejectedEvenWhenCalledPngAndDeclaredAsAnImage() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);

        upload(tenantId, adminId, "logo.png", "image/png", svg).andExpect(status().isBadRequest());
        upload(tenantId, adminId, "logo.svg", "image/svg+xml", svg).andExpect(status().isBadRequest());

        assertThat(storedKeyOrNull(tenantId)).isNull();
        assertThat(filesUnder(tenantId)).isEmpty();
    }

    @Test
    @Tag("REQ-PERS-03")
    void aFileWithAFakeExtensionButNoImageInsideIsRejected() throws Exception {
        upload(tenantId, adminId, "logo.png", "image/png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest());
        upload(tenantId, adminId, "logo.jpg", "image/jpeg", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0})
                .andExpect(status().isBadRequest());

        assertThat(filesUnder(tenantId)).isEmpty();
    }

    @Test
    @Tag("REQ-PERS-03")
    void aValidImageOverTheSizeLimitIsRejectedWith413AndNothingIsStored() throws Exception {
        byte[] bigPng = TestImages.noisyPng(1000, 1000);
        assertThat(bigPng.length).isGreaterThan(2 * 1024 * 1024);

        upload(tenantId, adminId, "big.png", "image/png", bigPng).andExpect(status().isPayloadTooLarge());

        assertThat(storedKeyOrNull(tenantId)).isNull();
        assertThat(filesUnder(tenantId)).isEmpty();
    }

    @Test
    @Tag("REQ-PERS-03")
    void anImageWiderOrTallerThanTheLimitIsRejected() throws Exception {
        upload(tenantId, adminId, "wide.png", "image/png", TestImages.image("png", 1025, 10)).andExpect(status().isBadRequest());
        upload(tenantId, adminId, "tall.jpg", "image/jpeg", TestImages.image("jpg", 10, 1025)).andExpect(status().isBadRequest());

        assertThat(filesUnder(tenantId)).isEmpty();
    }

    @Test
    @Tag("REQ-PERS-03")
    void aHostileFileNameDoesNotInfluenceWhereTheLogoIsStored() throws Exception {
        upload(tenantId, adminId, "../../../../outside.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isOk());

        assertThat(storedKey(tenantId)).matches("tenants/" + tenantId + "/logo-[0-9a-f]{32}\\.png");
        assertThat(Files.exists(STORAGE_ROOT.resolveSibling("outside.png"))).isFalse();
    }

    @Test
    @Tag("REQ-PERS-03")
    void aRequestWithoutAFilePartIsRejected() throws Exception {
        mockMvc.perform(multipart("/organizations/{tenantId}/theme/logo", tenantId)
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PERS-03")
    void replacingTheLogoRemovesThePreviousFile() throws Exception {
        upload(tenantId, adminId, "a.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isOk());
        String first = storedKey(tenantId);

        upload(tenantId, adminId, "b.jpg", "image/jpeg", TestImages.image("jpg", 16, 16)).andExpect(status().isOk());

        assertThat(storedKey(tenantId)).isNotEqualTo(first);
        assertThat(Files.exists(STORAGE_ROOT.resolve(first))).isFalse();
        assertThat(filesUnder(tenantId)).hasSize(1);
    }

    @Test
    @Tag("REQ-PERS-03")
    void aPaletteChangeKeepsTheLogo() throws Exception {
        upload(tenantId, adminId, "a.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isOk());
        String key = storedKey(tenantId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/organizations/{tenantId}/theme", tenantId)
                        .header("X-User-Id", adminId.toString()).header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json").content("{\"paletteId\":\"default\"}"))
                .andExpect(status().isOk());

        assertThat(storedKey(tenantId)).isEqualTo(key);
        download(tenantId, userId).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0} uploading a logo")
    @EnumSource(value = Membership.Role.class, names = {"MANAGER", "USER"})
    @Tag("REQ-PERS-01")
    void onlyAnAdminCanUploadTheLogo(Membership.Role role) throws Exception {
        UUID caller = role == Membership.Role.MANAGER ? managerId : userId;

        upload(tenantId, caller, "logo.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isForbidden());

        assertThat(storedKeyOrNull(tenantId)).isNull();
        assertThat(filesUnder(tenantId)).isEmpty();
    }

    @Test
    @Tag("REQ-PERS-01")
    void anAdminWithoutWriteAccessCannotUploadTheLogo() throws Exception {
        UUID readOnlyAdmin = UUID.randomUUID();
        insertMembership(tenantId, readOnlyAdmin, "ADMIN", true);

        upload(tenantId, readOnlyAdmin, "logo.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-PERS-01")
    void aNonMemberCannotReadTheLogo() throws Exception {
        upload(tenantId, adminId, "logo.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isOk());

        download(tenantId, UUID.randomUUID()).andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-PERS-01")
    void aTenantWithoutALogoHasNoneToDownload() throws Exception {
        download(tenantId, userId).andExpect(status().isNotFound());
    }

    @Test
    @Tag("REQ-AUTH-04")
    void aTokenForAnotherTenantCannotUploadOrReadTheLogo() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);
        upload(tenantId, adminId, "logo.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isOk());

        mockMvc.perform(multipart("/organizations/{tenantId}/theme/logo", tenantId)
                        .file(new MockMultipartFile("file", "logo.png", "image/png", TestImages.image("png", 16, 16)))
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/organizations/{tenantId}/theme/logo", tenantId)
                        .header("X-User-Id", otherAdmin.toString()).header("X-Tenant-Id", otherTenantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Tag("REQ-DATA-03")
    void eachTenantHasItsOwnLogoUnderItsOwnKeyPrefix() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenantId = createOrganization("Other Co", otherAdmin);
        byte[] mine = TestImages.image("png", 16, 16);
        byte[] theirs = TestImages.image("png", 24, 24);

        upload(tenantId, adminId, "logo.png", "image/png", mine).andExpect(status().isOk());
        upload(otherTenantId, otherAdmin, "logo.png", "image/png", theirs).andExpect(status().isOk());

        assertThat(storedKey(tenantId)).startsWith("tenants/" + tenantId + "/");
        assertThat(storedKey(otherTenantId)).startsWith("tenants/" + otherTenantId + "/");
        assertThat(download(tenantId, adminId).andReturn().getResponse().getContentAsByteArray()).isEqualTo(mine);
        assertThat(download(otherTenantId, otherAdmin).andReturn().getResponse().getContentAsByteArray()).isEqualTo(theirs);
    }

    @Test
    @Tag("REQ-BILL-04")
    void aTenantInTheGracePeriodCannotChangeItsLogo() throws Exception {
        accessStatusResponse = "{\"status\":\"READ_ONLY\"}";

        upload(tenantId, adminId, "logo.png", "image/png", TestImages.image("png", 16, 16)).andExpect(status().isForbidden());
    }

    private ResultActions upload(UUID tenant, UUID caller, String fileName, String contentType, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/organizations/{tenantId}/theme/logo", tenant)
                .file(new MockMultipartFile("file", fileName, contentType, content))
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                })
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString()));
    }

    private ResultActions download(UUID tenant, UUID caller) throws Exception {
        return mockMvc.perform(get("/organizations/{tenantId}/theme/logo", tenant)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tenant.toString()));
    }

    private List<Path> filesUnder(UUID tenant) throws IOException {
        Path dir = STORAGE_ROOT.resolve("tenants").resolve(tenant.toString());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    private String storedKey(UUID tenant) throws Exception {
        String key = storedKeyOrNull(tenant);
        assertThat(key).as("the stored logo key").isNotNull();
        return key;
    }

    private String storedKeyOrNull(UUID tenant) throws Exception {
        return (String) query(tenant, "SELECT theme_json->>'logoKey' AS k FROM org.tenant_settings WHERE tenant_id = ?", tenant).get("k");
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
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            jdbc.update("INSERT INTO org.memberships (id, tenant_id, user_id, role, read_only) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant, user, role, readOnly);
        }
    }

    private Map<String, Object> query(UUID tenant, String sql, Object... args) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            return jdbc.queryForMap(sql, args);
        }
    }
}
