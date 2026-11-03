package com.saas.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogPartitionMaintenance;
import com.saas.notification.export.ActivityExportRequested;
import com.saas.notification.export.ActivityLogExportService;
import com.saas.notification.export.ExportTimings;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.outbox.OutboxRelay;
import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.containers.RabbitMqTestContainer;
import com.saas.testsupport.events.EventAssertions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CSV export of the tenant activity journal (REQ-MON-05, REQ-SCALE-03). Requests go through MockMvc with the
 * identity headers the gateway would set; background jobs travel through the real outbox, a real RabbitMQ
 * and the real worker, with the relay driven by the test ({@link #drainOutbox}). Whether a request runs
 * inline or in the background is steered through {@link ExportTimings}: the threshold is one second, so a
 * measured cost of 2000 ms per row sends any non-empty export to the background and 0 ms keeps it inline.
 * The shared test Postgres user is a superuser and bypasses RLS, so what is proved here for isolation is
 * the explicit tenant filter (see {@link JournalFixtures}).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // the shared test Postgres is used as a superuser, so the role's search_path (set by the real
        // topology) is missing; the outbox writer uses an unqualified table name
        "spring.datasource.hikari.connection-init-sql=SET search_path TO notif,public",
        "saas.export.async-threshold=PT1S",
        "saas.export.max-rows=40",
        "saas.export.retry-backoff-ms=20",
        // the email queue is shared with every other context of the run, whose consumer must not take this context's messages
        "saas.email.listener-auto-startup=false"
})
@AutoConfigureMockMvc
class ActivityLogExportIT implements PostgresTestContainer, RabbitMqTestContainer {

    private static final String EXPORTS = "/notifications/activity-log/exports";
    private static final String HEADER = "id,event_type,actor_user_id,created_at,metadata\r\n";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ExportTimings timings;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private ActivityLogPartitionMaintenance partitions;

    private UUID tenantId;
    private UUID admin;
    private UUID actorA;
    private UUID actorB;

    @BeforeEach
    void freshTenant() {
        partitions.ensureUpcomingPartitions();
        tenantId = UUID.randomUUID();
        admin = UUID.randomUUID();
        actorA = UUID.randomUUID();
        actorB = UUID.randomUUID();
    }

    // ---- who may export (REQ-MON-05) ----

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-DATA-03")
    void anAdminGetsTheirTenantsJournalAsACsvFileRightAwayWhenItIsSmall() throws Exception {
        inline();
        UUID first = seed("LOGIN", actorA, 1);
        UUID second = seed("MEMBER_ROLE_CHANGED", actorB, 2);
        seedFor(UUID.randomUUID(), "LOGIN", actorA, 3);

        MvcResult result = export(tenantId, admin, "ADMIN", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"activity-log.csv\"");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(body(result)).isEqualTo(HEADER
                + line(second, "MEMBER_ROLE_CHANGED", actorB, 2)
                + line(first, "LOGIN", actorA, 1));
    }

    @Test
    @Tag("REQ-MON-05")
    void anEmptyJournalExportsAsAFileWithOnlyTheHeader() throws Exception {
        inline();

        MvcResult result = export(tenantId, admin, "ADMIN", "{}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result)).isEqualTo(HEADER);
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-MON-04")
    void theExportCanBeNarrowedWithTheSameFiltersAsTheReport() throws Exception {
        inline();
        seed("LOGIN", actorA, 1);
        UUID wanted = seed("MEMBER_ROLE_CHANGED", actorA, 2);
        seed("MEMBER_ROLE_CHANGED", actorB, 3);
        seed("MEMBER_ROLE_CHANGED", actorA, 10);

        MvcResult result = export(tenantId, admin, "ADMIN", "{\"eventTypes\":[\"MEMBER_ROLE_CHANGED\"],\"actorUserId\":\""
                + actorA + "\",\"from\":\"" + at(2) + "\",\"to\":\"" + at(3) + "\"}");

        assertThat(body(result)).isEqualTo(HEADER + line(wanted, "MEMBER_ROLE_CHANGED", actorA, 2));
    }

    @Test
    @Tag("REQ-MON-05")
    void theMetadataOfAnEntryIsExportedAsOneQuotedCell() throws Exception {
        inline();
        JournalFixtures.insert(jdbc, tenantId, "MEMBER_ROLE_CHANGED", actorA, "{\"newRole\":\"MANAGER\"}", JournalFixtures.MONTH_START.plus(Duration.ofHours(1)));

        String text = body(export(tenantId, admin, "ADMIN", null));

        assertThat(text.split("\r\n")).hasSize(2);
        assertThat(text).contains(",\"{\"\"newRole\"\": \"\"MANAGER\"\"}\"\r\n");
    }

    @ParameterizedTest(name = "role {0} cannot export")
    @ValueSource(strings = {"MANAGER", "USER", "READER", "admin", "ROLE_ADMIN", ""})
    @Tag("REQ-MON-05")
    @Tag("REQ-MGR-04")
    void onlyAnAdminCanExportSoEveryOtherRoleIsRefusedAndNothingIsQueued(String role) throws Exception {
        background();
        seed("LOGIN", actorA, 1);

        MvcResult result = export(tenantId, admin, role, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).doesNotContain(actorA.toString()).doesNotContain("LOGIN");
        assertThat(jobCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    @Tag("REQ-MON-05")
    void aManagerWhoCanReadTheJournalStillCannotFollowUpOnAnExport() throws Exception {
        background();
        seed("LOGIN", actorA, 1);
        UUID job = requestBackgroundExport();

        assertThat(status(tenantId, admin, "MANAGER", job).getResponse().getStatus()).isEqualTo(403);
        assertThat(download(tenantId, admin, "MANAGER", job).getResponse().getStatus()).isEqualTo(403);
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"X-User-Id", "X-Tenant-Id", "X-Role"})
    @Tag("REQ-MON-05")
    @Tag("REQ-AUTH-05")
    void aRequestWithoutTheGatewaysIdentityIsRefusedAsUnauthenticated(String missing) throws Exception {
        inline();
        seed("LOGIN", actorA, 1);

        MockHttpServletRequestBuilder request = post(EXPORTS).contentType("application/json").content("{}");
        if (!missing.equals("X-User-Id")) {
            request.header("X-User-Id", admin.toString());
        }
        if (!missing.equals("X-Tenant-Id")) {
            request.header("X-Tenant-Id", tenantId.toString());
        }
        if (!missing.equals("X-Role")) {
            request.header("X-Role", "ADMIN");
        }
        MvcResult result = mockMvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(result)).doesNotContain("LOGIN");
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-AUTH-05")
    void aMalformedIdentityIsRefusedAsUnauthenticated() throws Exception {
        MvcResult result = mockMvc.perform(post(EXPORTS).contentType("application/json").content("{}")
                .header("X-User-Id", "not-a-uuid").header("X-Tenant-Id", tenantId.toString()).header("X-Role", "ADMIN")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- the tenant is the token's, never the request's (REQ-AUTH-05, REQ-DATA-03) ----

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-AUTH-05")
    void aRequestBodyCannotNameAnotherTenant() throws Exception {
        inline();
        UUID other = UUID.randomUUID();
        seedFor(other, "LOGIN", actorB, 1);

        for (String field : List.of("tenantId", "tenant_id", "tenant")) {
            MvcResult result = export(tenantId, admin, "ADMIN", "{\"" + field + "\":\"" + other + "\"}");

            assertThat(result.getResponse().getStatus()).as(field).isEqualTo(400);
            assertThat(body(result)).as(field).doesNotContain(actorB.toString());
        }
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {
            "{\"from\":\"yesterday\"}",
            "{\"to\":20261102}",
            "{\"from\":\"2026-02-01T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"}",
            "{\"eventTypes\":[\"not valid\"]}",
            "{\"eventTypes\":\"LOGIN\"}",
            "{\"eventTypes\":[1]}",
            "{\"actorUserId\":\"nobody\"}",
            "[]",
            "{not json"})
    @Tag("REQ-MON-05")
    @Tag("REQ-MON-04")
    void aFilterThatDoesNotParseIsRejectedWithABadRequest(String body) throws Exception {
        inline();
        seed("LOGIN", actorA, 1);

        MvcResult result = export(tenantId, admin, "ADMIN", body);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result)).doesNotContain("LOGIN");
    }

    // ---- background jobs (REQ-MON-05, REQ-SCALE-03) ----

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-SCALE-03")
    void anExportOverTheThresholdIsAcceptedAsAJobAndQueuedInTheOutboxWithIdsOnly() throws Exception {
        background();
        UUID entry = seed("LOGIN", actorA, 1);

        MvcResult result = export(tenantId, admin, "ADMIN", "{\"actorUserId\":\"" + actorA + "\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        JsonNode accepted = objectMapper.readTree(body(result));
        UUID jobId = UUID.fromString(accepted.get("jobId").asText());
        assertThat(accepted.get("status").asText()).isEqualTo("PENDING");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(EXPORTS + "/" + jobId);
        assertThat(body(result)).doesNotContain(entry.toString()).doesNotContain("LOGIN");

        assertThat(jdbc.queryForMap("select status, requested_by, content, row_count from notif.export_jobs where id = ?", jobId))
                .containsEntry("status", "PENDING").containsEntry("requested_by", admin)
                .containsEntry("content", null).containsEntry("row_count", null);
        assertThat(jdbc.queryForObject("select tenant_id from notif.export_jobs where id = ?", UUID.class, jobId)).isEqualTo(tenantId);

        var outbox = jdbc.queryForMap("select destination, channel, event_type, payload, published_at from notif.outbox where tenant_id = ?", tenantId);
        assertThat(outbox).containsEntry("destination", "RABBITMQ").containsEntry("channel", ActivityLogExportService.COMMAND_EXCHANGE)
                .containsEntry("event_type", "ACTIVITY_EXPORT_REQUESTED").containsEntry("published_at", null);
        JsonNode command = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(command.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(command.get("payload").get("jobId").asText()).isEqualTo(jobId.toString());
        assertThat(command.get("payload").get("requestedBy").asText()).isEqualTo(admin.toString());
        assertThat(names(command.get("payload"))).containsExactlyInAnyOrder("jobId", "tenantId", "requestedBy");

        assertThat(status(tenantId, admin, "ADMIN", jobId).getResponse().getStatus()).isEqualTo(200);
        MvcResult early = download(tenantId, admin, "ADMIN", jobId);
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-SCALE-03")
    @Tag("REQ-DATA-03")
    void aBackgroundExportIsProducedByTheWorkerAndTheRequesterIsToldItIsReady() throws Exception {
        background();
        UUID first = seed("LOGIN", actorA, 1);
        UUID second = seed("INVITATION_CREATED", actorB, 2);
        seedFor(UUID.randomUUID(), "LOGIN", actorA, 3);
        long backgroundRuns = timerCount("background");

        UUID jobId = requestBackgroundExport();
        drainOutbox();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jobStatus(jobId)).isEqualTo("READY"));
        String expected = HEADER + line(second, "INVITATION_CREATED", actorB, 2) + line(first, "LOGIN", actorA, 1);

        MvcResult done = status(tenantId, admin, "ADMIN", jobId);
        JsonNode job = objectMapper.readTree(body(done));
        assertThat(job.get("status").asText()).isEqualTo("READY");
        assertThat(job.get("rowCount").asLong()).isEqualTo(2);
        assertThat(job.get("completedAt").isNull()).isFalse();
        assertThat(job.get("expiresAt").isNull()).isFalse();

        MvcResult file = download(tenantId, admin, "ADMIN", jobId);
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentType()).startsWith("text/csv");
        assertThat(file.getResponse().getHeader("Content-Disposition")).contains("attachment");
        assertThat(body(file)).isEqualTo(expected);

        var notifications = jdbc.queryForList("select title, message, level, is_read, tenant_id from notif.in_app_notifications where user_id = ?", admin);
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).containsEntry("title", "Export ready").containsEntry("level", "INFO")
                .containsEntry("is_read", false).containsEntry("tenant_id", tenantId);
        assertThat((String) notifications.get(0).get("message")).contains(jobId.toString());
        assertThat(jdbc.queryForObject("select count(*) from notif.in_app_notifications where tenant_id = ? and user_id <> ?", Integer.class, tenantId, admin)).isZero();

        seed("LOGIN", actorA, 4);
        assertThat(body(download(tenantId, admin, "ADMIN", jobId))).as("the file is the journal as it was when the job ran").isEqualTo(expected);
        assertThat(timerCount("background")).isGreaterThan(backgroundRuns);
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-SCALE-03")
    void theDecisionFollowsTheMeasuredTimeNotTheOperation() throws Exception {
        seed("LOGIN", actorA, 1);

        inline();
        long beforeCheap = timerCount("inline");
        int whenCheap = export(tenantId, admin, "ADMIN", null).getResponse().getStatus();
        long afterCheap = timerCount("inline");
        background();
        long beforeSlow = timerCount("inline");
        int whenSlow = export(tenantId, admin, "ADMIN", null).getResponse().getStatus();
        long afterSlow = timerCount("inline");
        inline();
        int whenCheapAgain = export(tenantId, admin, "ADMIN", null).getResponse().getStatus();

        assertThat(List.of(whenCheap, whenSlow, whenCheapAgain)).containsExactly(200, 202, 200);
        assertThat(afterCheap).as("an inline export is timed").isEqualTo(beforeCheap + 1);
        assertThat(afterSlow).as("a background request is not timed as inline").isEqualTo(beforeSlow);
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-SCALE-03")
    void anExportThatTakesLongerThanAnticipatedMakesTheNextOneRunInTheBackground() throws Exception {
        seed("LOGIN", actorA, 1);
        inline();
        assertThat(export(tenantId, admin, "ADMIN", null).getResponse().getStatus()).isEqualTo(200);

        for (int i = 0; i < 20; i++) {
            timings.record(10, Duration.ofSeconds(30), "inline");
        }

        assertThat(export(tenantId, admin, "ADMIN", null).getResponse().getStatus()).isEqualTo(202);
    }

    @Test
    @Tag("REQ-MON-05")
    void aFilterThatMatchesMoreThanAnExportMayHoldIsRefusedAndNothingIsQueued() throws Exception {
        background();
        for (int i = 1; i <= 41; i++) {
            seed("LOGIN", actorA, i % 20 + 1);
        }

        MvcResult result = export(tenantId, admin, "ADMIN", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(result)).contains("41").contains("40").contains("narrow the filter");
        assertThat(jobCount()).isZero();
        assertThat(outboxCount()).isZero();

        MvcResult narrowed = export(tenantId, admin, "ADMIN", "{\"to\":\"" + at(2) + "\"}");
        assertThat(narrowed.getResponse().getStatus()).isEqualTo(202);
    }

    // ---- who can follow a job (REQ-MON-05) ----

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-DATA-03")
    void aJobIsVisibleOnlyToTheUserWhoRequestedItAndOnlyInItsTenant() throws Exception {
        background();
        seed("LOGIN", actorA, 1);
        UUID jobId = requestBackgroundExport();
        drainOutbox();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jobStatus(jobId)).isEqualTo("READY"));

        UUID otherAdminSameTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        for (MvcResult result : List.of(
                status(tenantId, otherAdminSameTenant, "ADMIN", jobId), download(tenantId, otherAdminSameTenant, "ADMIN", jobId),
                status(otherTenant, admin, "ADMIN", jobId), download(otherTenant, admin, "ADMIN", jobId),
                status(tenantId, admin, "ADMIN", UUID.randomUUID()), download(tenantId, admin, "ADMIN", UUID.randomUUID()))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(body(result)).doesNotContain("LOGIN").doesNotContain(actorA.toString());
        }
        assertThat(download(tenantId, admin, "ADMIN", jobId).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-MON-05")
    void aJobIdThatIsNotAnIdIsABadRequest() throws Exception {
        assertThat(mockMvc.perform(get(EXPORTS + "/not-an-id").header("X-User-Id", admin.toString())
                .header("X-Tenant-Id", tenantId.toString()).header("X-Role", "ADMIN")).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-MON-05")
    void aFinishedExportIsGoneOnceItsRetentionPassesAndTheTenantsNextExportCleansItUp() throws Exception {
        background();
        seed("LOGIN", actorA, 1);
        UUID jobId = requestBackgroundExport();
        drainOutbox();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jobStatus(jobId)).isEqualTo("READY"));

        jdbc.update("update notif.export_jobs set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(60)), jobId);

        assertThat(status(tenantId, admin, "ADMIN", jobId).getResponse().getStatus()).isEqualTo(404);
        assertThat(download(tenantId, admin, "ADMIN", jobId).getResponse().getStatus()).isEqualTo(404);
        requestBackgroundExport();
        assertThat(jdbc.queryForObject("select count(*) from notif.export_jobs where id = ?", Integer.class, jobId)).isZero();
    }

    // ---- delivery guarantees of the worker (REQ-EVT-05, REQ-EVT-07) ----

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-EVT-05")
    void aCommandDeliveredTwiceProducesOneFileAndOneNotification() throws Exception {
        background();
        seed("LOGIN", actorA, 1);
        UUID first = requestBackgroundExport();
        String command = jdbc.queryForObject("select payload from notif.outbox where tenant_id = ? and payload like ?", String.class,
                tenantId, "%" + first + "%");
        drainOutbox();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jobStatus(first)).isEqualTo("READY"));

        rabbitTemplate.convertAndSend(ActivityLogExportService.COMMAND_EXCHANGE, "", command);
        UUID second = requestBackgroundExport();
        drainOutbox();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jobStatus(second)).isEqualTo("READY"));

        assertThat(jdbc.queryForObject("select count(*) from notif.in_app_notifications where tenant_id = ? and user_id = ?", Integer.class, tenantId, admin))
                .isEqualTo(2);
    }

    @Test
    @Tag("REQ-MON-05")
    @Tag("REQ-EVT-07")
    void aJobThatKeepsFailingIsRetriedThenDeadLetteredAndNobodyIsToldItIsReady() throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("insert into notif.export_jobs (id, tenant_id, requested_by, status, filter, created_at, expires_at)"
                        + " values (?, ?, ?, 'PENDING', ?::jsonb, now(), now() + interval '1 day')",
                jobId, tenantId, admin, "{\"eventTypes\":[\"not valid\"]}");
        String command = objectMapper.writeValueAsString(EventEnvelope.forTenant(ActivityExportRequested.EVENT_TYPE, 1, tenantId,
                new ActivityExportRequested(jobId, tenantId, admin)));

        rabbitTemplate.convertAndSend(ActivityLogExportService.COMMAND_EXCHANGE, "", command);

        assertThat(EventAssertions.awaitRabbitMessage("notif.export-jobs.dlq", message -> message.contains(jobId.toString()))).contains(jobId.toString());
        assertThat(jobStatus(jobId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from notif.in_app_notifications where user_id = ?", Integer.class, admin)).isZero();
    }

    // ---- helpers ----

    /** Every export measured as free, so a small one runs inline. */
    private void inline() {
        measuredCost(0);
    }

    /** Every export measured as 2 s per row, so any non-empty one is over the 1 s threshold. */
    private void background() {
        measuredCost(2000);
    }

    private void measuredCost(long millisPerRow) {
        for (int i = 0; i < 20; i++) {
            timings.record(10, Duration.ofMillis(millisPerRow * 10), "inline");
        }
    }

    private long timerCount(String mode) {
        return Timer.builder("activity.export.duration").tag("mode", mode).register(meterRegistry).count();
    }

    private UUID seed(String type, UUID actor, int hours) {
        return seedFor(tenantId, type, actor, hours);
    }

    private UUID seedFor(UUID tenant, String type, UUID actor, int hours) {
        return JournalFixtures.insert(jdbc, tenant, type, actor, "{}", JournalFixtures.MONTH_START.plus(Duration.ofHours(hours)));
    }

    private static Instant at(int hours) {
        return JournalFixtures.MONTH_START.plus(Duration.ofHours(hours));
    }

    private static String line(UUID id, String type, UUID actor, int hours) {
        return id + "," + type + "," + actor + "," + at(hours) + ",{}\r\n";
    }

    private MvcResult export(UUID tenant, UUID user, String role, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(EXPORTS).header("X-User-Id", user.toString())
                .header("X-Tenant-Id", tenant.toString()).header("X-Role", role);
        if (body != null) {
            request.contentType("application/json").content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private UUID requestBackgroundExport() throws Exception {
        MvcResult result = export(tenantId, admin, "ADMIN", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return UUID.fromString(objectMapper.readTree(body(result)).get("jobId").asText());
    }

    private MvcResult status(UUID tenant, UUID user, String role, UUID jobId) throws Exception {
        return mockMvc.perform(get(EXPORTS + "/" + jobId).header("X-User-Id", user.toString())
                .header("X-Tenant-Id", tenant.toString()).header("X-Role", role)).andReturn();
    }

    private MvcResult download(UUID tenant, UUID user, String role, UUID jobId) throws Exception {
        return mockMvc.perform(get(EXPORTS + "/" + jobId + "/download").header("X-User-Id", user.toString())
                .header("X-Tenant-Id", tenant.toString()).header("X-Role", role)).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String jobStatus(UUID jobId) {
        return jdbc.queryForObject("select status from notif.export_jobs where id = ?", String.class, jobId);
    }

    private int jobCount() {
        return jdbc.queryForObject("select count(*) from notif.export_jobs where tenant_id = ?", Integer.class, tenantId);
    }

    private int outboxCount() {
        return jdbc.queryForObject("select count(*) from notif.outbox where tenant_id = ?", Integer.class, tenantId);
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Stands in for the scheduled relay (disabled in tests): publishes what the outbox holds to RabbitMQ. */
    private void drainOutbox() {
        OutboxRelay relay = new OutboxRelay(dataSource, "notif", null, rabbitTemplate::convertAndSend);
        while (relay.pollAndPublishOnce(100) > 0) {
            // keep going until the outbox is empty
        }
    }
}
