package com.saas.auth;

import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.time.MutableClock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password reset: 1h single-use token invalidated by a new request (REQ-PWD-02), anti-enumeration
 * (REQ-PWD-03), revokes refresh tokens (REQ-PWD-04), rate limit (REQ-PWD-05), the same password policy
 * as registration (REQ-PWD-06), confirmation command (REQ-PWD-07), no tenant journal entry (REQ-PWD-08),
 * user-initiated only (REQ-PWD-09). Same dedicated-container setup as {@link RegistrationIT}.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@AutoConfigureMockMvc
class PasswordResetIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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
    @Tag("REQ-PWD-02")
    void aNewRequestInvalidatesThePreviousToken() throws Exception {
        String email = register();
        String ip = uniqueIp();

        forgotPassword(email, ip);
        String firstToken = latestResetToken(email);

        forgotPassword(email, ip);
        String secondToken = latestResetToken(email);
        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(resetPassword(firstToken, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(resetPassword(secondToken, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isOk());
    }

    @Test
    @Tag("REQ-PWD-02")
    void aTokenCannotBeUsedTwice() throws Exception {
        String email = register();
        forgotPassword(email, uniqueIp());
        String token = latestResetToken(email);

        mockMvc.perform(resetPassword(token, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isOk());
        mockMvc.perform(resetPassword(token, "Another-Correct-Battery-Staple-9"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PWD-03")
    void theResponseIsIdenticalForAnExistingAndANonExistentEmail() throws Exception {
        String existing = register();

        var existingResult = mockMvc.perform(forgotPasswordRequest(existing, uniqueIp()))
                .andExpect(status().isOk()).andReturn();
        var missingResult = mockMvc.perform(forgotPasswordRequest("nobody-" + UUID.randomUUID() + "@test.local", uniqueIp()))
                .andExpect(status().isOk()).andReturn();

        assertThat(existingResult.getResponse().getContentAsString())
                .isEqualTo(missingResult.getResponse().getContentAsString());
    }

    @Test
    @Tag("REQ-PWD-04")
    void resettingThePasswordRevokesEveryRefreshToken() throws Exception {
        String email = register();
        UUID userId = userIdOf(email);
        insertRefreshToken(userId);
        assertThat(countRefreshTokens(userId)).isEqualTo(1);

        forgotPassword(email, uniqueIp());
        String token = latestResetToken(email);
        mockMvc.perform(resetPassword(token, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isOk());

        assertThat(countRefreshTokens(userId)).isZero();
    }

    @Test
    @Tag("REQ-PWD-05")
    void moreThanThreeRequestsPerHourForTheSameEmailAreRejected() throws Exception {
        String email = register();
        String ip = uniqueIp();

        forgotPassword(email, ip);
        forgotPassword(email, ip);
        forgotPassword(email, ip);
        mockMvc.perform(forgotPasswordRequest(email, ip)).andExpect(status().is(429));
    }

    @Test
    @Tag("REQ-PWD-05")
    void moreThanTenRequestsPerHourFromTheSameIpAreRejected() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 10; i++) {
            forgotPassword("ip-flood-" + UUID.randomUUID() + "@test.local", ip);
        }
        mockMvc.perform(forgotPasswordRequest("ip-flood-" + UUID.randomUUID() + "@test.local", ip))
                .andExpect(status().is(429));
    }

    @Test
    @Tag("REQ-PWD-06")
    void theResetPasswordMustAlsoSatisfyThePasswordPolicy() throws Exception {
        String email = register();
        forgotPassword(email, uniqueIp());
        String token = latestResetToken(email);

        mockMvc.perform(resetPassword(token, "short1234"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Tag("REQ-PWD-07")
    @Tag("REQ-PWD-08")
    void resettingThePasswordCommandsAConfirmationEmailWithNoTenant() throws Exception {
        String email = register();
        forgotPassword(email, uniqueIp());
        String token = latestResetToken(email);

        mockMvc.perform(resetPassword(token, "Correct-Horse-Battery-Staple-9"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, payload FROM auth.outbox WHERE event_type = 'SEND_PASSWORD_CHANGED_CONFIRMATION' "
                        + "ORDER BY created_at DESC LIMIT 1");
        assertThat(row.get("tenant_id")).isNull();
        assertThat(row.get("payload").toString()).contains(email);
    }

    @Test
    @Tag("REQ-PWD-09")
    void thereIsNoAdminInitiatedResetEndpoint() {
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.getPatternValues().stream()
                        .anyMatch(pattern -> pattern.contains("admin") && pattern.contains("reset")));
    }

    private String register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@test.local";
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"Correct-Horse-Battery-Staple-9\"}"))
                .andExpect(status().isCreated());
        return email;
    }

    private void forgotPassword(String email, String ip) throws Exception {
        mockMvc.perform(forgotPasswordRequest(email, ip)).andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder forgotPasswordRequest(String email, String ip) {
        return post("/auth/forgot-password")
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\"}")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    private MockHttpServletRequestBuilder resetPassword(String token, String newPassword) {
        return post("/auth/reset-password")
                .contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private String latestResetToken(String email) {
        return jdbc.queryForObject(
                "SELECT t.token FROM auth.password_reset_tokens t JOIN auth.users u ON u.id = t.user_id "
                        + "WHERE u.email = ? ORDER BY t.created_at DESC LIMIT 1",
                String.class, email);
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM auth.users WHERE email = ?", UUID.class, email);
    }

    private void insertRefreshToken(UUID userId) {
        jdbc.update("INSERT INTO auth.refresh_tokens "
                        + "(id, user_id, token, expires_at, tenant_id, role, read_only, tenant_name, created_at) "
                        + "VALUES (?, ?, ?, now() + interval '7 days', ?, 'ADMIN', false, 'Test Tenant', now())",
                UUID.randomUUID(), userId, UUID.randomUUID().toString(), UUID.randomUUID());
    }

    private int countRefreshTokens(UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth.refresh_tokens WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private static String uniqueIp() {
        return "10.0." + (int) (Math.random() * 255) + "." + (int) (Math.random() * 255);
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
