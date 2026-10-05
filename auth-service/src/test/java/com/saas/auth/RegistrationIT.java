package com.saas.auth;

import com.saas.testsupport.containers.SharedContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration: password policy, no PII in the event, no tenant created (REQ-PWD-01, REQ-PWD-06,
 * REQ-EVT-08). Runs the real topology script (not the generic @ServiceConnection container): the
 * outbox writer relies on auth_svc's search_path, which only the real role provides.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class RegistrationIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

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

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @Tag("REQ-PWD-06")
    void aPasswordShorterThan12CharactersIsRejected() throws Exception {
        mockMvc.perform(register(uniqueEmail(), "Sh0rt1234"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PWD-06")
    void aCommonCompromisedPasswordIsRejected() throws Exception {
        mockMvc.perform(register(uniqueEmail(), "password123456"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PWD-06")
    @Tag("REQ-PWD-01")
    void aValidPasswordIsAccepted() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(register(email, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isCreated());

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM auth.users WHERE email = ?", Integer.class, email);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Tag("REQ-EVT-02")
    @Tag("REQ-EVT-08")
    void theRegistrationEventHasNoEmailAndNoTenant() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(register(email, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isCreated());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, payload FROM auth.outbox WHERE event_type = 'USER_REGISTERED' ORDER BY created_at DESC LIMIT 1");
        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("payload").toString()).doesNotContain(email, "email");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String email, String password) {
        return post("/auth/register")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.local";
    }
}
