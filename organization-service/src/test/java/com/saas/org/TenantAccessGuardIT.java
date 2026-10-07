package com.saas.org;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A tenant's write access is checked synchronously against billing-service (REQ-BILL-04). billing-service
 * is stubbed with a tiny JDK HttpServer, same trick as auth-service's LoginFlowIT, so the access status
 * can change between calls without a real billing-service running.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=TenantAccessGuardIT"
})
@AutoConfigureMockMvc
class TenantAccessGuardIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String accessStatusResponse = "{\"status\":\"ACTIVE\"}";
    private static HttpServer billingStub = startBillingStub();

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    @Tag("REQ-BILL-04")
    void aReadOnlyTenantRejectsAWrite() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = createOrganization("Grace Co", adminId);

        accessStatusResponse = "{\"status\":\"READ_ONLY\"}";
        try {
            mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                            .header("X-User-Id", adminId.toString())
                            .header("X-Tenant-Id", tenantId.toString())
                            .contentType("application/json")
                            .content("{\"name\":\"Renamed\"}"))
                    .andExpect(status().isForbidden());
        } finally {
            accessStatusResponse = "{\"status\":\"ACTIVE\"}";
        }
    }

    @Test
    @Tag("REQ-BILL-04")
    void aSuspendedTenantRejectsAWrite() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = createOrganization("Suspended Co", adminId);

        accessStatusResponse = "{\"status\":\"SUSPENDED\"}";
        try {
            mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                            .header("X-User-Id", adminId.toString())
                            .header("X-Tenant-Id", tenantId.toString())
                            .contentType("application/json")
                            .content("{\"name\":\"Renamed\"}"))
                    .andExpect(status().isForbidden());
        } finally {
            accessStatusResponse = "{\"status\":\"ACTIVE\"}";
        }
    }

    @Test
    @Tag("REQ-BILL-04")
    void anActiveTenantAllowsAWrite() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID tenantId = createOrganization("Active Co", adminId);

        mockMvc.perform(patch("/organizations/{tenantId}", tenantId)
                        .header("X-User-Id", adminId.toString())
                        .header("X-Tenant-Id", tenantId.toString())
                        .contentType("application/json")
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk());
    }

    private UUID createOrganization(String name, UUID creatorUserId) throws Exception {
        var result = mockMvc.perform(post("/organizations")
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\",\"creatorUserId\":\"" + creatorUserId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map body = mapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return UUID.fromString((String) body.get("id"));
    }
}
