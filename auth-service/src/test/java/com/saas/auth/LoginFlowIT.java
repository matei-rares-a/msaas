package com.saas.auth;

import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login flow: 0/1/N tenants, opaque single-use loginSessionId, synchronous re-check on tenant
 * selection (REQ-AUTH-01, REQ-AUTH-02, REQ-AUTH-03, REQ-EVT-09, REQ-SCALE-01). org-service is stubbed
 * with a tiny JDK HttpServer so the membership list can be changed between the two login calls.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class LoginFlowIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String orgStubResponse = "[]";
    private static HttpServer orgStub = startOrgStub();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableClock clock;

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

        String globalUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_global");
        System.setProperty("spring.datasource.url", globalUrl);
        System.setProperty("spring.datasource.username", "auth_svc");
        System.setProperty("spring.datasource.password", "pw");
        return container;
    }

    private static HttpServer startOrgStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/users/", exchange -> {
                byte[] body = orgStubResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/internal/tenants/", exchange -> {
                byte[] body = "{\"name\":\"Test Tenant\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            System.setProperty("org-service.base-url", "http://localhost:" + server.getAddress().getPort());
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        if (orgStub != null) {
            orgStub.stop(0);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Tag("REQ-AUTH-03")
    void zeroTenantsReturnsAnEmptyList() throws Exception {
        String email = register();
        orgStubResponse = "[]";

        var result = login(email).andExpect(status().isOk()).andReturn();
        Map body = readBody(result);
        assertThat((Iterable<?>) body.get("tenants")).isEmpty();
        assertThat(body).doesNotContainKey("accessToken");
    }

    @Test
    @Tag("REQ-AUTH-01")
    @Tag("REQ-SCALE-01")
    void oneTenantReturnsATokenDirectly() throws Exception {
        String email = register();
        orgStubResponse = membershipsJson(new UUID[] {UUID.randomUUID()}, new String[] {"ADMIN"});

        var result = login(email).andExpect(status().isOk()).andReturn();
        Map body = readBody(result);
        assertThat(body.get("accessToken")).isNotNull();
    }

    @Test
    @Tag("REQ-AUTH-02")
    void manyTenantsReturnsAChoiceAndTheSecondCallIssuesTheToken() throws Exception {
        String email = register();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        orgStubResponse = membershipsJson(new UUID[] {tenantA, tenantB}, new String[] {"ADMIN", "USER"});

        var loginResult = login(email).andExpect(status().isOk()).andReturn();
        Map loginBody = readBody(loginResult);
        assertThat((Iterable<?>) loginBody.get("tenants")).hasSize(2);
        String loginSessionId = (String) loginBody.get("loginSessionId");
        assertThat(loginSessionId).isNotBlank();

        var chosen = selectTenant(loginSessionId, tenantA).andExpect(status().isOk()).andReturn();
        assertThat(readBody(chosen).get("accessToken")).isNotNull();
    }

    @Test
    @Tag("REQ-AUTH-02")
    void aLoginSessionCannotBeReused() throws Exception {
        String email = register();
        UUID tenantA = UUID.randomUUID();
        orgStubResponse = membershipsJson(new UUID[] {tenantA, UUID.randomUUID()}, new String[] {"ADMIN", "USER"});

        String loginSessionId = (String) readBody(login(email).andReturn()).get("loginSessionId");
        selectTenant(loginSessionId, tenantA).andExpect(status().isOk());

        selectTenant(loginSessionId, tenantA).andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-AUTH-02")
    void anExpiredLoginSessionIsRejected() throws Exception {
        String email = register();
        UUID tenantA = UUID.randomUUID();
        orgStubResponse = membershipsJson(new UUID[] {tenantA, UUID.randomUUID()}, new String[] {"ADMIN", "USER"});

        String loginSessionId = (String) readBody(login(email).andReturn()).get("loginSessionId");
        clock.advance(Duration.ofMinutes(6));

        selectTenant(loginSessionId, tenantA).andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-EVT-09")
    void aUserRemovedFromTheTenantMeanwhileGetsNoTokenForIt() throws Exception {
        String email = register();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        orgStubResponse = membershipsJson(new UUID[] {tenantA, tenantB}, new String[] {"ADMIN", "USER"});

        String loginSessionId = (String) readBody(login(email).andReturn()).get("loginSessionId");

        // removed from tenantA between the two calls
        orgStubResponse = membershipsJson(new UUID[] {tenantB}, new String[] {"USER"});

        selectTenant(loginSessionId, tenantA).andExpect(status().isBadRequest());
    }

    private String register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-Battery-Staple-9\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private org.springframework.test.web.servlet.ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-Battery-Staple-9\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions selectTenant(String loginSessionId, UUID tenantId) throws Exception {
        return mockMvc.perform(post("/auth/login/tenant")
                .contentType("application/json")
                .content("{\"loginSessionId\":\"" + loginSessionId + "\",\"tenantId\":\"" + tenantId + "\"}"));
    }

    private static Map readBody(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return new ObjectMapper().readValue(result.getResponse().getContentAsString(), Map.class);
    }

    private static String membershipsJson(UUID[] tenantIds, String[] roles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tenantIds.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"tenantId\":\"").append(tenantIds[i]).append("\",\"role\":\"").append(roles[i]).append("\",\"readOnly\":false}");
        }
        return sb.append("]").toString();
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock clock() {
            return MutableClock.startingNow();
        }
    }
}
