package com.saas.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.auth.events.MemberOffboardedListener;
import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Erasing the global identity (REQ-DATA-01): never when offboarding from one tenant of several, always
 * once the last tenant is gone or the user asks for it from outside every tenant. org-service is
 * stubbed with a tiny JDK HttpServer (same trick as {@link LoginFlowIT}) so the membership list can
 * change between calls; the listener is driven directly with the event's wire format.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class IdentityErasureIT {

    private static final String PASSWORD = "Correct-Horse-Battery-Staple-9";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String orgStubResponse = "[]";
    private static volatile int orgStubStatus = 200;
    private static HttpServer orgStub = startOrgStub();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MemberOffboardedListener listener;

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
                exchange.sendResponseHeaders(orgStubStatus, orgStubStatus == 200 ? body.length : -1);
                if (orgStubStatus == 200) {
                    exchange.getResponseBody().write(body);
                }
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
    @Tag("REQ-DATA-01")
    void leavingTheLastTenantErasesTheIdentityAndItCanNeverLogInAgain() throws Exception {
        UUID tenantA = UUID.randomUUID();
        String email = register();
        UUID userId = userId(email);
        orgStubResponse = memberships(tenantA);
        String refreshA = refreshTokenOf(login(email));

        orgStubResponse = "[]"; // org already removed the membership before the event is published
        listener.onOrgEvent(offboardedEvent(UUID.randomUUID(), tenantA, userId));

        Map<String, Object> row = jdbc.queryForMap("select email, is_active from auth.users where id = ?", userId);
        assertThat((String) row.get("email")).doesNotContain(email).endsWith("@anonymized.invalid");
        assertThat(row.get("is_active")).isEqualTo(false);
        login(email).andExpect(status().is4xxClientError());
        refresh(refreshA).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("select count(*) from auth.refresh_tokens where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @Tag("REQ-DATA-01")
    void leavingOneOfTwoTenantsKeepsTheIdentityAndTheOtherTenantsSessions() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        String email = register();
        UUID userId = userId(email);
        orgStubResponse = memberships(tenantA);
        String refreshA = refreshTokenOf(login(email));
        orgStubResponse = memberships(tenantB);
        String refreshB = refreshTokenOf(login(email));

        orgStubResponse = memberships(tenantB); // removed from A only
        listener.onOrgEvent(offboardedEvent(UUID.randomUUID(), tenantA, userId));

        assertThat(jdbc.queryForObject("select email from auth.users where id = ?", String.class, userId)).isEqualTo(email);
        refresh(refreshA).andExpect(status().is4xxClientError()); // the removed tenant's session is gone
        refresh(refreshB).andExpect(status().isOk());
        login(email).andExpect(status().isOk()); // the user still logs in to the remaining tenant
    }

    @Test
    @Tag("REQ-DATA-01")
    void anUnreachableOrgServiceNeverErasesAnIdentityAndTheEventIsRetried() throws Exception {
        UUID tenantA = UUID.randomUUID();
        String email = register();
        UUID userId = userId(email);
        String event = offboardedEvent(UUID.randomUUID(), tenantA, userId);

        orgStubStatus = 503;
        try {
            assertThatThrownBy(() -> listener.onOrgEvent(event)).isInstanceOf(RuntimeException.class);
        } finally {
            orgStubStatus = 200;
        }
        assertThat(jdbc.queryForObject("select email from auth.users where id = ?", String.class, userId)).isEqualTo(email);

        // the failed attempt left no idempotency record, so the very same event succeeds on redelivery
        orgStubResponse = "[]";
        listener.onOrgEvent(event);
        assertThat(jdbc.queryForObject("select is_active from auth.users where id = ?", Boolean.class, userId)).isFalse();
    }

    @Test
    @Tag("REQ-EVT-05")
    void aRedeliveredOffboardingEventIsProcessedOnce() throws Exception {
        UUID tenantA = UUID.randomUUID();
        String email = register();
        UUID userId = userId(email);
        UUID eventId = UUID.randomUUID();
        orgStubResponse = memberships(tenantA);
        String refreshA = refreshTokenOf(login(email));

        orgStubResponse = memberships(UUID.randomUUID());
        listener.onOrgEvent(offboardedEvent(eventId, tenantA, userId));
        // a new session for the same tenant after the first delivery must survive a duplicate delivery
        orgStubResponse = memberships(tenantA);
        String freshRefreshA = refreshTokenOf(login(email));
        listener.onOrgEvent(offboardedEvent(eventId, tenantA, userId));

        refresh(refreshA).andExpect(status().is4xxClientError());
        refresh(freshRefreshA).andExpect(status().isOk());
    }

    @Test
    @Tag("REQ-DATA-01")
    void aUserInNoOrganizationCanEraseTheirOwnAccount() throws Exception {
        String email = register();
        UUID userId = userId(email);
        orgStubResponse = "[]";

        mockMvc.perform(delete("/auth/account").header("X-User-Id", userId.toString())).andExpect(status().isNoContent());

        assertThat((String) jdbc.queryForObject("select email from auth.users where id = ?", String.class, userId))
                .endsWith("@anonymized.invalid");
        login(email).andExpect(status().is4xxClientError());
    }

    @Test
    @Tag("REQ-DATA-01")
    void aUserWhoStillBelongsToAnOrganizationCannotEraseTheAccount() throws Exception {
        String email = register();
        UUID userId = userId(email);
        orgStubResponse = memberships(UUID.randomUUID());

        mockMvc.perform(delete("/auth/account").header("X-User-Id", userId.toString())).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select email from auth.users where id = ?", String.class, userId)).isEqualTo(email);
        login(email).andExpect(status().isOk());
    }

    private String offboardedEvent(UUID eventId, UUID tenantId, UUID userId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", "MEMBER_OFFBOARDED",
                "version", 1,
                "tenantId", tenantId.toString(),
                "occurredAt", Instant.now().toString(),
                "payload", Map.of("tenantId", tenantId.toString(), "userId", userId.toString())));
    }

    private String register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("select id from auth.users where email = ?", UUID.class, email);
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType("application/json")
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private String refreshTokenOf(ResultActions login) throws Exception {
        MvcResult result = login.andExpect(status().isOk()).andReturn();
        return (String) objectMapper.readValue(result.getResponse().getContentAsString(), Map.class).get("refreshToken");
    }

    private static String memberships(UUID tenantId) {
        return "[{\"tenantId\":\"" + tenantId + "\",\"role\":\"USER\",\"readOnly\":false}]";
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
