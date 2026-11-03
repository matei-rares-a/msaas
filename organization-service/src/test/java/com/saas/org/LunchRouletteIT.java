package com.saas.org;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.lunch.LunchJobs;
import com.saas.org.lunch.LunchService;
import com.saas.shared.events.ActivityEventCatalog;
import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.db.RlsAssertions;
import com.saas.testsupport.time.MutableClock;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lunch roulette module (REQ-MOD-01, REQ-LUNCH-01..05, REQ-NOTIF-03) through the real HTTP layer and the real jobs,
 * with the identity headers the gateway sets from the token, against the real topology (so the service's own non-superuser
 * role and RLS apply and the cross-tenant job reads through {@code org_auth_reader}). Time is a {@link MutableClock}
 * that starts on Monday 2026-03-02, 10:00 in Bucharest; with the default settings (Thursday, 11:00) a round closes on
 * Thursday 2026-03-05 at 09:00 UTC. The randomness is seeded, billing-service is a stub whose answer the test changes,
 * the scheduled jobs are switched off and driven by hand, and what the module tells the other services is read from the outbox.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "saas.lunch.formation-cron=-",
        "saas.lunch.purge-cron=-",
        "test.marker=LunchRouletteIT"
})
@AutoConfigureMockMvc
class LunchRouletteIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String accessStatus = "ACTIVE";
    private static final HttpServer billingStub = startBillingStub();

    /** Monday 10:00 in Bucharest. */
    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");
    /** Thursday 11:00 in Bucharest: when the first round closes with the default settings. */
    private static final Instant CUTOFF = Instant.parse("2026-03-05T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MutableClock clock;
    @Autowired
    private LunchJobs jobs;
    @Autowired
    private LunchService lunchService;

    private UUID tenant;
    private UUID admin;

    record Resp(int status, JsonNode body) {
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
                byte[] body = ("{\"status\":\"" + accessStatus + "\"}").getBytes(StandardCharsets.UTF_8);
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
        billingStub.stop(0);
        postgres.stop();
    }

    @BeforeEach
    void freshTenantWithTheModuleOn() throws Exception {
        accessStatus = "ACTIVE";
        clock.setInstant(NOW);
        admin = UUID.randomUUID();
        tenant = createOrganization(admin);
        assertThat(call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", admin, tenant, Map.of("enabled", true)).status()).isEqualTo(200);
    }

    // =====================================================================================================
    // the module switch (REQ-MOD-01)
    // =====================================================================================================

    @Test
    @Tag("REQ-MOD-01")
    void theLunchModuleIsOffByDefaultAndSwitchedByTheAdminAloneLikeAnyModule() throws Exception {
        UUID freshAdmin = UUID.randomUUID();
        UUID fresh = createOrganization(freshAdmin);
        UUID manager = addMember(fresh, "MANAGER", false);

        assertThat(call(HttpMethod.GET, "/modules", manager, fresh, null).body().get("modules").get("LUNCH_ROULETTE").asBoolean()).isFalse();
        assertThat(call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", manager, fresh, Map.of("enabled", true)).status()).as("a MANAGER does not switch it").isEqualTo(403);
        assertThat(call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", freshAdmin, fresh, Map.of("enabled", true)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/modules", manager, fresh, null).body().get("modules").get("LUNCH_ROULETTE").asBoolean()).isTrue();
        assertThat(call(HttpMethod.GET, "/modules", manager, fresh, null).body().get("modules").get("LEAVE").asBoolean()).as("the other module is its own switch").isFalse();
    }

    @Test
    @Tag("REQ-MOD-01")
    void aModuleThatIsOffDoesNotExistForTheTenant() throws Exception {
        UUID offAdmin = UUID.randomUUID();
        UUID off = createOrganization(offAdmin);
        UUID user = addMember(off, "USER", false);
        UUID round = UUID.randomUUID();

        for (UUID caller : List.of(offAdmin, user)) {
            assertThat(call(HttpMethod.GET, "/lunch/config", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/lunch/rounds/current", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/lunch/rounds", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/lunch/rounds/" + round, caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.POST, "/lunch/rounds/current/cancel", caller, off, null).status()).isEqualTo(404);
        }
        assertThat(call(HttpMethod.PUT, "/lunch/config", offAdmin, off, Map.of("day", "FRIDAY", "cutoff", "10:00", "groupSize", 3)).status()).isEqualTo(404);
        assertThat(rows(off, "select count(*) from org.lunch_rounds")).isZero();
    }

    @Test
    @Tag("REQ-MOD-01")
    void switchingTheModuleOffKeepsItsRoundsAndSwitchingItOnBringsThemBack() throws Exception {
        List<UUID> members = members(4);
        enrollAll(members);
        closeFirstRound();
        UUID roundId = latestRoundId(admin);

        assertThat(call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", admin, tenant, Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + roundId, members.get(0), tenant, null).status()).isEqualTo(404);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants")).as("kept while it is off").isEqualTo(4);

        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", admin, tenant, Map.of("enabled", true));
        JsonNode detail = call(HttpMethod.GET, "/lunch/rounds/" + roundId, members.get(0), tenant, null).body();
        assertThat(detail.get("round").get("status").asText()).isEqualTo("FORMED");
        assertThat(detail.get("myGroup")).hasSize(2);
    }

    // =====================================================================================================
    // the weekly settings (REQ-LUNCH-01)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-01")
    void theSettingsStartOnThursdayAtElevenWithGroupsOfTwoAndAnyMemberReadsThem() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID readOnly = addMember(tenant, "USER", true);

        for (UUID caller : List.of(user, readOnly, admin)) {
            JsonNode config = call(HttpMethod.GET, "/lunch/config", caller, tenant, null).body();
            assertThat(config.get("day").asText()).isEqualTo("THURSDAY");
            assertThat(config.get("cutoff").asText()).isEqualTo("11:00");
            assertThat(config.get("groupSize").asInt()).isEqualTo(2);
            assertThat(config.get("timezone").asText()).isEqualTo("Europe/Bucharest");
        }
        assertThat(call(HttpMethod.GET, "/lunch/config", UUID.randomUUID(), tenant, null).status()).as("a non-member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-LUNCH-01")
    void aManagerAndAnAdminSetTheWeekdayTheCutoffAndTheGroupSize() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);

        Resp byManager = call(HttpMethod.PUT, "/lunch/config", manager, tenant, Map.of("day", "FRIDAY", "cutoff", "09:30", "groupSize", 3));
        Resp byAdmin = call(HttpMethod.PUT, "/lunch/config", admin, tenant, Map.of("day", "WEDNESDAY", "cutoff", "23:59", "groupSize", 4));

        assertThat(byManager.status()).isEqualTo(200);
        assertThat(byManager.body().get("day").asText()).isEqualTo("FRIDAY");
        assertThat(byManager.body().get("cutoff").asText()).isEqualTo("09:30");
        assertThat(byManager.body().get("groupSize").asInt()).isEqualTo(3);
        assertThat(byAdmin.status()).isEqualTo(200);
        JsonNode stored = call(HttpMethod.GET, "/lunch/config", manager, tenant, null).body();
        assertThat(stored.get("day").asText()).isEqualTo("WEDNESDAY");
        assertThat(stored.get("cutoff").asText()).isEqualTo("23:59");
        assertThat(stored.get("groupSize").asInt()).isEqualTo(4);
    }

    @Test
    @Tag("REQ-LUNCH-01")
    void aUserARoleLessOrReadOnlyMemberAndANonMemberCannotConfigure() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);

        for (UUID caller : List.of(user, readOnlyManager, readOnlyAdmin, UUID.randomUUID())) {
            assertThat(call(HttpMethod.PUT, "/lunch/config", caller, tenant, Map.of("day", "FRIDAY", "cutoff", "09:30", "groupSize", 3)).status()).as(caller.toString()).isEqualTo(403);
        }
        assertThat(call(HttpMethod.GET, "/lunch/config", user, tenant, null).body().get("day").asText()).isEqualTo("THURSDAY");
    }

    @Test
    @Tag("REQ-LUNCH-01")
    void theGroupSizeIsBetweenTwoAndFourTheDayAnEnglishWeekdayAndTheCutoffATimeOfDay() throws Exception {
        for (int size : new int[] {0, 1, 5, 100, -2}) {
            assertThat(config("FRIDAY", "10:00", size).status()).as("size " + size).isEqualTo(400);
        }
        for (String day : new String[] {"friday", "Friday", "FRI", "VINERI", "", " FRIDAY", "FRIDAY;"}) {
            assertThat(config(day, "10:00", 2).status()).as("day [" + day + "]").isEqualTo(400);
        }
        for (String cutoff : new String[] {"24:00", "25:30", "9:00", "09:60", "11", "11:00:00", "11.00", "noon", "", " 11:00", "11:00 "}) {
            assertThat(config("FRIDAY", cutoff, 2).status()).as("cutoff [" + cutoff + "]").isEqualTo(400);
        }
        assertThat(call(HttpMethod.PUT, "/lunch/config", admin, tenant, "{\"day\":\"FRIDAY\",\"cutoff\":\"10:00\"}").status()).as("no size").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/lunch/config", admin, tenant, "{\"cutoff\":\"10:00\",\"groupSize\":2}").status()).as("no day").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/lunch/config", admin, tenant, "{\"day\":\"FRIDAY\",\"groupSize\":2}").status()).as("no cutoff").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/lunch/config", admin, tenant, "{\"day\":\"FRIDAY\",\"cutoff\":\"10:00\",\"groupSize\":\"two\"}").status()).isEqualTo(400);
        assertThat(config("MONDAY", "00:00", 2).status()).as("the first minute of the week").isEqualTo(200);
        assertThat(config("SUNDAY", "23:59", 4).status()).as("the last").isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/lunch/config", admin, tenant, null).body().get("day").asText()).isEqualTo("SUNDAY");
    }

    @Test
    @Tag("REQ-LUNCH-01")
    @Tag("REQ-MOD-01")
    void changingTheSettingsMovesTheCutoffOfTheOpenRoundAndTheTimeZoneDoesToo() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        assertThat(Instant.parse(current(member).get("deadlineAt").asText())).isEqualTo(CUTOFF);

        config("FRIDAY", "15:00", 2);
        assertThat(Instant.parse(current(member).get("deadlineAt").asText())).as("Friday 15:00 in Bucharest").isEqualTo(Instant.parse("2026-03-06T13:00:00Z"));

        call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", "America/New_York"));
        assertThat(Instant.parse(current(member).get("deadlineAt").asText())).as("Friday 15:00 in New York").isEqualTo(Instant.parse("2026-03-06T20:00:00Z"));
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isEqualTo(1);
    }

    @Test
    @Tag("REQ-LUNCH-01")
    @Tag("REQ-MOD-01")
    void theCutoffRespectsTheTenantsTimeZoneSoTheSameInstantIsBeforeItInOneTenantAndAfterItInAnother() throws Exception {
        UUID bucharestMember = addMember(tenant, "USER", false);
        UUID bucharestOther = addMember(tenant, "USER", false);
        UUID newYorkAdmin = UUID.randomUUID();
        UUID newYork = createOrganization(newYorkAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", newYorkAdmin, newYork, Map.of("enabled", true));
        call(HttpMethod.PUT, "/timezone", newYorkAdmin, newYork, Map.of("timezone", "America/New_York"));
        UUID newYorkMember = addMember(newYork, "USER", false);
        UUID newYorkOther = addMember(newYork, "USER", false);
        for (UUID member : List.of(bucharestMember, bucharestOther)) {
            call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        }
        for (UUID member : List.of(newYorkMember, newYorkOther)) {
            call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, newYork, null);
        }

        clock.setInstant(Instant.parse("2026-03-05T10:00:00Z")); // Thursday 12:00 in Bucharest, 05:00 in New York
        jobs.formDueRounds();

        assertThat(roundStatus(tenant)).as("11:00 Bucharest has passed").isEqualTo("FORMED");
        assertThat(roundStatus(newYork)).as("11:00 New York has not").isEqualTo("OPEN");
        assertThat(Instant.parse(call(HttpMethod.GET, "/lunch/rounds/current", newYorkMember, newYork, null).body().get("deadlineAt").asText()))
                .isEqualTo(Instant.parse("2026-03-05T16:00:00Z"));
    }

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-LUNCH-01")
    void theTimeZoneAlsoDecidesWhichWeekItIsSoSundayNightUtcCanAlreadyBeNextWeekAtTheTenant() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        clock.setInstant(Instant.parse("2026-03-08T22:30:00Z")); // Monday 00:30 in Bucharest

        assertThat(current(member).get("weekStart").asText()).isEqualTo("2026-03-09");
        call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", "America/New_York"));
        assertThat(current(member).get("weekStart").asText()).as("still Sunday evening in New York, and the week's cut-off is over").isEqualTo("2026-03-09");
        config("SUNDAY", "23:00", 2);
        assertThat(current(member).get("weekStart").asText()).as("a Sunday 23:00 cut-off in New York is still ahead").isEqualTo("2026-03-02");
    }

    // =====================================================================================================
    // enrolling and withdrawing (REQ-LUNCH-02)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-02")
    void readingTheOpenRoundBeforeAnyoneEnrolledShowsAnEmptyOpenWeekAndCreatesNothing() throws Exception {
        UUID member = addMember(tenant, "USER", false);

        JsonNode round = current(member);

        assertThat(round.get("id").isNull()).isTrue();
        assertThat(round.get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(Instant.parse(round.get("deadlineAt").asText())).isEqualTo(CUTOFF);
        assertThat(round.get("status").asText()).isEqualTo("OPEN");
        assertThat(round.get("participantCount").asInt()).isZero();
        assertThat(round.get("enrolled").asBoolean()).isFalse();
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).as("a read does not write").isZero();
        assertThat(call(HttpMethod.GET, "/lunch/rounds/current", member, tenant, null).status()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void aMemberEnrollsExplicitlyAndTheRoundIsCreatedForTheWeek() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID bob = addMember(tenant, "USER", false);

        Resp enrolled = call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", alice, tenant, null);

        assertThat(enrolled.status()).isEqualTo(200);
        assertThat(enrolled.body().get("id").isNull()).isFalse();
        assertThat(enrolled.body().get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(enrolled.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(enrolled.body().get("participantCount").asInt()).isEqualTo(1);
        assertThat(enrolled.body().get("enrolled").asBoolean()).isTrue();
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", alice)).isEqualTo(1);
        JsonNode seenByBob = current(bob);
        assertThat(seenByBob.get("participantCount").asInt()).isEqualTo(1);
        assertThat(seenByBob.get("enrolled").asBoolean()).as("a member is not enrolled by anybody else's enrolment").isFalse();
        assertThat(seenByBob.toString()).as("who is in is not shown while the round is open").doesNotContain(alice.toString());
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void anyRoleEnrollsIncludingAdminsAndManagersAndEnrollingTwiceIsTheSameAsOnce() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);

        for (UUID member : List.of(user, manager, admin)) {
            assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).status()).isEqualTo(200);
        }
        Resp again = call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", user, tenant, null);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("participantCount").asInt()).isEqualTo(3);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants")).isEqualTo(3);
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isEqualTo(1);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void aMemberWithdrawsBeforeTheCutoffAndWithdrawingWhenNotEnrolledChangesNothing() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID bob = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", alice, tenant, null);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", bob, tenant, null);

        Resp withdrawn = call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", alice, tenant, null);
        Resp again = call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", alice, tenant, null);

        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("enrolled").asBoolean()).isFalse();
        assertThat(withdrawn.body().get("participantCount").asInt()).isEqualTo(1);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("participantCount").asInt()).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", bob)).as("another member's enrolment stays").isEqualTo(1);
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", alice, tenant, null).body().get("participantCount").asInt()).as("and can join again").isEqualTo(2);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void withdrawingBeforeAnyRoundExistsIsAnOpenEmptyWeekAndCreatesNothing() throws Exception {
        UUID member = addMember(tenant, "USER", false);

        Resp withdrawn = call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null);

        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("id").isNull()).isTrue();
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isZero();
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void justBeforeTheCutoffAMemberCanStillEnrollAndWithdrawButAtTheCutoffTheWeekIsClosedAndTheNextOneIsOpen() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        UUID other = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", other, tenant, null);

        clock.setInstant(CUTOFF.minusSeconds(1));
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).body().get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null).body().get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).body().get("participantCount").asInt()).isEqualTo(2);

        clock.setInstant(CUTOFF);
        JsonNode open = current(member);
        assertThat(open.get("weekStart").asText()).as("the week is over for joining").isEqualTo("2026-03-09");
        assertThat(Instant.parse(open.get("deadlineAt").asText())).isEqualTo(CUTOFF.plus(Duration.ofDays(7)));
        assertThat(open.get("enrolled").asBoolean()).isFalse();
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void afterTheCutoffAnEnrolmentGoesToNextWeeksRoundAndWithdrawingCannotUndoAnAlreadyFormedRound() throws Exception {
        List<UUID> members = members(4);
        enrollAll(members);
        closeFirstRound();
        UUID firstRound = latestRoundId(admin);

        Resp withdrawn = call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", members.get(0), tenant, null);
        Resp joinedNext = call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", members.get(0), tenant, null);

        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("weekStart").asText()).as("it is next week's round it concerns").isEqualTo("2026-03-09");
        assertThat(joinedNext.body().get("weekStart").asText()).isEqualTo("2026-03-09");
        assertThat(joinedNext.body().get("participantCount").asInt()).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where round_id = ?", firstRound)).as("the formed round is untouched").isEqualTo(4);
        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + firstRound, members.get(0), tenant, null).body().get("myGroup")).hasSize(2);
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isEqualTo(2);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void thereIsOneRoundPerWeekSoTwelveMembersEnrollingAtOnceShareOne() throws Exception {
        List<UUID> members = members(12);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (UUID member : members) {
                attempts.add(() -> call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).status());
            }
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants")).isEqualTo(12);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void theReadOnlyFlagBlocksEnrollingAndWithdrawingEvenAboutTheMembersOwnEnrolmentButNotReading() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        update(tenant, "update org.memberships set read_only = true where user_id = ?", member);
        UUID alwaysReadOnly = addMember(tenant, "USER", true);

        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", alwaysReadOnly, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null).status()).as("not even to leave").isEqualTo(403);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", member)).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", alwaysReadOnly)).isZero();
        assertThat(current(member).get("enrolled").asBoolean()).as("they still read").isTrue();
        assertThat(call(HttpMethod.GET, "/lunch/rounds", alwaysReadOnly, tenant, null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", UUID.randomUUID(), tenant, null).status()).as("a non-member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    @Tag("REQ-AUTH-05")
    void whoEnrollsIsAlwaysTheCallerWhateverTheRequestSays() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID victim = addMember(tenant, "USER", false);

        Resp enrolled = callWithBody(HttpMethod.PUT, "/lunch/rounds/current/enrollment?userId=" + victim + "&user_id=" + victim, alice, tenant,
                "{\"userId\":\"" + victim + "\",\"user_id\":\"" + victim + "\"}");

        assertThat(enrolled.status()).isEqualTo(200);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", victim)).isZero();
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", alice)).isEqualTo(1);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    @Tag("REQ-MOD-01")
    void whileTheTenantIsInItsGracePeriodOrSuspendedNoWriteIsAccepted() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);

        for (String blocked : List.of("READ_ONLY", "SUSPENDED")) {
            accessStatus = blocked;
            assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", manager, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/lunch/rounds/current/cancel", manager, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.PUT, "/lunch/config", manager, tenant, Map.of("day", "FRIDAY", "cutoff", "10:00", "groupSize", 3)).status()).as(blocked).isEqualTo(403);
            assertThat(current(member).get("participantCount").asInt()).as("reads stay open: " + blocked).isEqualTo(1);
            assertThat(call(HttpMethod.GET, "/lunch/config", member, tenant, null).status()).isEqualTo(200);
        }
        accessStatus = "ACTIVE";
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", manager, tenant, null).status()).isEqualTo(200);
    }

    // =====================================================================================================
    // cancelling a round (REQ-LUNCH-02)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-02")
    @Tag("REQ-NOTIF-03")
    void aManagerOrAdminCancelsTheOpenRoundBeforeTheCutoffAndTheParticipantsAreToldWhichEnrollingAgainIsRefused() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        List<UUID> members = members(3);
        enrollAll(members);

        Resp cancelled = call(HttpMethod.POST, "/lunch/rounds/current/cancel", manager, tenant, null);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().get("cancelReason").asText()).isEqualTo("BY_APPROVER");
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where status = 'CANCELLED' and cancelled_by = ?", manager)).isEqualTo(1);
        List<JsonNode> told = events(tenant, "LUNCH_ROUND_CANCELLED");
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("reason").asText()).isEqualTo("BY_APPROVER");
        assertThat(told.get(0).get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(texts(told.get(0).get("participantUserIds"))).containsExactlyInAnyOrderElementsOf(members.stream().map(UUID::toString).toList());
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", members.get(0), tenant, null).status()).as("no second round in a week").isEqualTo(409);
        assertThat(call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", members.get(0), tenant, null).status()).isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/lunch/rounds/current/cancel", admin, tenant, null).status()).as("already cancelled").isEqualTo(409);
        jobs.formDueRounds();
        clock.setInstant(CUTOFF.plusSeconds(60));
        jobs.formDueRounds();
        assertThat(events(tenant, "LUNCH_GROUPS_FORMED")).as("a cancelled round is never formed").isEmpty();
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", members.get(0), tenant, null).body().get("weekStart").asText()).as("next week is open again").isEqualTo("2026-03-09");
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void anAdminCancelsToo() throws Exception {
        enrollAll(members(2));

        assertThat(call(HttpMethod.POST, "/lunch/rounds/current/cancel", admin, tenant, null).body().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void cancellingAWeekNobodyHasJoinedClosesItWithoutAnyoneToTell() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID member = addMember(tenant, "USER", false);

        Resp cancelled = call(HttpMethod.POST, "/lunch/rounds/current/cancel", manager, tenant, null);

        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(events(tenant, "LUNCH_ROUND_CANCELLED")).as("nobody to tell").isEmpty();
        assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).status()).isEqualTo(409);
    }

    @Test
    @Tag("REQ-LUNCH-02")
    void aUserARoleLessOrReadOnlyMemberAndANonMemberCannotCancel() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);
        enrollAll(List.of(user));

        for (UUID caller : List.of(user, readOnlyManager, readOnlyAdmin, UUID.randomUUID())) {
            assertThat(call(HttpMethod.POST, "/lunch/rounds/current/cancel", caller, tenant, null).status()).as(caller.toString()).isEqualTo(403);
        }
        assertThat(current(user).get("status").asText()).isEqualTo("OPEN");
    }

    // =====================================================================================================
    // forming the groups at the cutoff (REQ-LUNCH-03)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-03")
    void atTheCutoffTheParticipantsAreSplitIntoGroupsOfTheConfiguredSizeAndNothingHappensBefore() throws Exception {
        List<UUID> members = members(6);
        config("THURSDAY", "11:00", 3);
        enrollAll(members);

        clock.setInstant(CUTOFF.minusSeconds(1));
        jobs.formDueRounds();
        assertThat(roundStatus(tenant)).as("the cut-off has not come").isEqualTo("OPEN");

        clock.setInstant(CUTOFF);
        jobs.formDueRounds();

        assertThat(roundStatus(tenant)).isEqualTo("FORMED");
        List<List<String>> groups = groupsOf(admin, latestRoundId(admin));
        assertThat(groups).hasSize(2).allSatisfy(group -> assertThat(group).hasSize(3));
        assertThat(groups.stream().flatMap(List::stream).toList()).containsExactlyInAnyOrderElementsOf(members.stream().map(UUID::toString).toList());
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where status = 'FORMED' and formed_at is not null")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} participants in groups of {1} -> {2}")
    @CsvSource(delimiter = '|', value = {"2|2|2", "5|2|3 2", "7|2|3 2 2", "4|3|2 2", "7|3|3 2 2", "5|4|3 2", "9|4|4 3 2", "3|4|3"})
    @Tag("REQ-LUNCH-03")
    void leftoversAreDistributedSoNoGroupIsEverSmallerThanTwo(int participants, int size, String expected) throws Exception {
        config("THURSDAY", "11:00", size);
        enrollAll(members(participants));

        closeFirstRound();

        List<Integer> sizes = groupsOf(admin, latestRoundId(admin)).stream().map(List::size).sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(sizes).isEqualTo(java.util.Arrays.stream(expected.split(" ")).map(Integer::parseInt).toList());
        assertThat(sizes).allMatch(s -> s >= 2 && s <= 4);
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void eachParticipantIsInExactlyOneGroupAndOnlyParticipantsAre() throws Exception {
        List<UUID> members = members(8);
        UUID notEnrolled = addMember(tenant, "USER", false);
        enrollAll(members);

        closeFirstRound();

        UUID round = latestRoundId(admin);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where round_id = ? and group_number is not null", round)).isEqualTo(8);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", notEnrolled)).isZero();
        List<String> seen = groupsOf(admin, round).stream().flatMap(List::stream).toList();
        assertThat(seen).hasSize(8).doesNotHaveDuplicates().doesNotContain(notEnrolled.toString());
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void aMemberWhoWithdrewIsNotGroupedAndOneWhoJoinedLateIsInTheLastMinute() throws Exception {
        List<UUID> members = members(5);
        enrollAll(members);
        call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", members.get(0), tenant, null);
        clock.setInstant(CUTOFF.minusSeconds(1));
        UUID late = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", late, tenant, null);

        clock.setInstant(CUTOFF);
        jobs.formDueRounds();

        List<String> seen = groupsOf(admin, latestRoundId(admin)).stream().flatMap(List::stream).toList();
        assertThat(seen).hasSize(5).contains(late.toString()).doesNotContain(members.get(0).toString());
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void withFewerThanTwoParticipantsTheRoundIsCancelledAndTheOneParticipantIsTold() throws Exception {
        UUID only = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", only, tenant, null);

        closeFirstRound();

        assertThat(roundStatus(tenant)).isEqualTo("CANCELLED");
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where cancel_reason = 'TOO_FEW_PARTICIPANTS' and cancelled_by is null")).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where group_number is not null")).isZero();
        List<JsonNode> told = events(tenant, "LUNCH_ROUND_CANCELLED");
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("reason").asText()).isEqualTo("TOO_FEW_PARTICIPANTS");
        assertThat(texts(told.get(0).get("participantUserIds"))).containsExactly(only.toString());
        assertThat(events(tenant, "LUNCH_GROUPS_FORMED")).isEmpty();
        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + latestRoundId(admin), only, tenant, null).body().get("round").get("cancelReason").asText()).isEqualTo("TOO_FEW_PARTICIPANTS");
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void withNoParticipantsAtAllTheRoundIsCancelledQuietlyAndTwoParticipantsIsEnough() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        UUID other = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null);
        closeFirstRound();
        assertThat(roundStatus(tenant)).isEqualTo("CANCELLED");
        assertThat(events(tenant, "LUNCH_ROUND_CANCELLED")).as("nobody to tell").isEmpty();

        clock.setInstant(NOW.plus(Duration.ofDays(7)));
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", other, tenant, null);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(7)));
        jobs.formDueRounds();

        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where status = 'FORMED'")).isEqualTo(1);
        assertThat(groupsOf(admin, latestRoundId(admin))).hasSize(1).first().satisfies(group -> assertThat(group).hasSize(2));
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void pairsFromTheRecentRoundsAreAvoidedWhileThereIsAWayToAvoidThem() throws Exception {
        List<UUID> four = members(4);
        Set<Set<String>> pairsSoFar = new HashSet<>();

        for (int week = 0; week < 3; week++) {
            clock.setInstant(NOW.plus(Duration.ofDays(7L * week)));
            for (UUID member : four) {
                assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).status()).isEqualTo(200);
            }
            clock.setInstant(CUTOFF.plus(Duration.ofDays(7L * week)));
            jobs.formDueRounds();

            for (List<String> group : groupsOf(admin, latestRoundId(admin))) {
                Set<String> pair = new HashSet<>(group);
                assertThat(pairsSoFar).as("week " + week + " must not repeat " + group).doesNotContain(pair);
                pairsSoFar.add(pair);
            }
        }
        assertThat(pairsSoFar).as("all six pairs of four people, over three weeks of two pairs").hasSize(6);

        clock.setInstant(NOW.plus(Duration.ofDays(21)));
        enrollAll(four);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(21)));
        jobs.formDueRounds();
        assertThat(groupsOf(admin, latestRoundId(admin))).as("a fourth week cannot avoid a repeat, and still forms valid groups").hasSize(2).allSatisfy(group -> assertThat(group).hasSize(2));
    }

    @Test
    @Tag("REQ-LUNCH-03")
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-EVT-02")
    void formingTheGroupsPublishesOneEventWithTheGroupsAsIdsAndNothingElse() throws Exception {
        List<UUID> members = members(5);
        enrollAll(members);

        closeFirstRound();

        List<JsonNode> formed = events(tenant, "LUNCH_GROUPS_FORMED");
        assertThat(formed).hasSize(1);
        JsonNode event = formed.get(0);
        assertThat(event.get("tenantId").asText()).isEqualTo(tenant.toString());
        assertThat(event.get("roundId").asText()).isEqualTo(latestRoundId(admin).toString());
        assertThat(event.get("weekStart").asText()).isEqualTo("2026-03-02");
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("tenantId", "roundId", "weekStart", "groups");
        List<String> inEvent = new ArrayList<>();
        event.get("groups").forEach(group -> group.forEach(member -> inEvent.add(member.asText())));
        assertThat(inEvent).containsExactlyInAnyOrderElementsOf(members.stream().map(UUID::toString).toList());
        assertThat(event.get("groups")).hasSize(2);
        assertThat(allOutbox(tenant)).doesNotContain("@").doesNotContainIgnoringCase("\"name\"").doesNotContainIgnoringCase("\"email\"");
        assertThat(outboxChannels(tenant, "LUNCH_GROUPS_FORMED")).containsExactly("KAFKA:org.events");
    }

    @Test
    @Tag("REQ-LUNCH-03")
    void theJobDoesNotFormARoundTwiceAndLeavesAnotherTenantsOpenRoundAlone() throws Exception {
        enrollAll(members(4));
        UUID otherAdmin = UUID.randomUUID();
        UUID other = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", otherAdmin, other, Map.of("enabled", true));
        call(HttpMethod.PUT, "/lunch/config", otherAdmin, other, Map.of("day", "FRIDAY", "cutoff", "23:00", "groupSize", 2));
        UUID otherMember = addMember(other, "USER", false);
        UUID otherMember2 = addMember(other, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", otherMember, other, null);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", otherMember2, other, null);

        closeFirstRound();
        long formedEvents = events(tenant, "LUNCH_GROUPS_FORMED").size();
        UUID round = latestRoundId(admin);
        clock.setInstant(CUTOFF.plusSeconds(3600));
        jobs.formDueRounds();

        assertThat(events(tenant, "LUNCH_GROUPS_FORMED")).hasSize((int) formedEvents).hasSize(1);
        assertThat(lunchService.closeDueRound(tenant, round, false)).isEqualTo(LunchService.Outcome.NOTHING_TO_DO);
        assertThat(roundStatus(other)).as("Friday 23:00 has not come").isEqualTo("OPEN");
        assertThat(lunchService.closeDueRound(tenant, UUID.randomUUID(), false)).as("an unknown round").isEqualTo(LunchService.Outcome.NOTHING_TO_DO);
        assertThat(lunchService.closeDueRound(other, roundIdOf(other), false)).as("a round not yet due").isEqualTo(LunchService.Outcome.NOTHING_TO_DO);
    }

    // =====================================================================================================
    // the grace period, suspension and a module that was switched off (REQ-MOD-01)
    // =====================================================================================================

    @ParameterizedTest(name = "billing status {0}")
    @ValueSource(strings = {"READ_ONLY", "SUSPENDED"})
    @Tag("REQ-MOD-01")
    @Tag("REQ-LUNCH-03")
    void inTheGracePeriodOrWhenSuspendedNoGroupsAreFormedAndTheOpenRoundIsCancelled(String billing) throws Exception {
        List<UUID> members = members(4);
        enrollAll(members);
        accessStatus = billing;

        closeFirstRound();

        assertThat(roundStatus(tenant)).isEqualTo("CANCELLED");
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where cancel_reason = 'TENANT_RESTRICTED'")).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where group_number is not null")).as("no groups").isZero();
        assertThat(events(tenant, "LUNCH_GROUPS_FORMED")).isEmpty();
        List<JsonNode> told = events(tenant, "LUNCH_ROUND_CANCELLED");
        assertThat(told).hasSize(1);
        assertThat(told.get(0).get("reason").asText()).isEqualTo("TENANT_RESTRICTED");
        assertThat(texts(told.get(0).get("participantUserIds"))).containsExactlyInAnyOrderElementsOf(members.stream().map(UUID::toString).toList());
    }

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-LUNCH-03")
    void aRestrictedTenantDoesNotStopAnotherTenantsRoundFromBeingFormedBecauseTheStatusIsAskedPerTenant() throws Exception {
        enrollAll(members(2));
        accessStatus = "READ_ONLY";
        closeFirstRound();
        accessStatus = "ACTIVE";
        clock.setInstant(NOW);

        List<UUID> others = new ArrayList<>();
        UUID otherAdmin = UUID.randomUUID();
        UUID other = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", otherAdmin, other, Map.of("enabled", true));
        for (int i = 0; i < 2; i++) {
            UUID member = addMember(other, "USER", false);
            others.add(member);
            call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, other, null);
        }
        clock.setInstant(CUTOFF.plusSeconds(120));
        jobs.formDueRounds();

        assertThat(roundStatus(tenant)).isEqualTo("CANCELLED");
        assertThat(roundStatus(other)).isEqualTo("FORMED");
    }

    @Test
    @Tag("REQ-MOD-01")
    void whenTheModuleWasSwitchedOffBeforeTheCutoffNoGroupsAreFormedEither() throws Exception {
        enrollAll(members(4));
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", admin, tenant, Map.of("enabled", false));

        closeFirstRound();

        assertThat(roundStatus(tenant)).isEqualTo("CANCELLED");
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where cancel_reason = 'MODULE_DISABLED'")).isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where group_number is not null")).isZero();
        assertThat(events(tenant, "LUNCH_GROUPS_FORMED")).isEmpty();
    }

    // =====================================================================================================
    // seeing the groups (REQ-LUNCH-04)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-04")
    void aParticipantSeesOnlyTheirOwnGroupAndAnApproverSeesEveryGroup() throws Exception {
        List<UUID> members = members(6);
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID outsider = addMember(tenant, "USER", false);
        config("THURSDAY", "11:00", 3);
        enrollAll(members);
        closeFirstRound();
        UUID round = latestRoundId(admin);

        List<List<String>> all = groupsOf(admin, round);
        assertThat(all).hasSize(2);
        assertThat(groupsOf(manager, round)).as("a MANAGER sees them all too").isEqualTo(all);
        for (UUID member : members) {
            JsonNode detail = call(HttpMethod.GET, "/lunch/rounds/" + round, member, tenant, null).body();
            List<String> mine = texts(detail.get("myGroup"));
            assertThat(mine).contains(member.toString()).isEqualTo(all.stream().filter(group -> group.contains(member.toString())).findFirst().orElseThrow());
            assertThat(detail.get("groups").isNull()).as("a participant does not see the other group").isTrue();
            assertThat(detail.toString()).doesNotContain(all.stream().filter(group -> !group.contains(member.toString())).findFirst().orElseThrow().get(0));
        }
        JsonNode seenByOutsider = call(HttpMethod.GET, "/lunch/rounds/" + round, outsider, tenant, null).body();
        assertThat(seenByOutsider.get("myGroup").isNull()).isTrue();
        assertThat(seenByOutsider.get("groups").isNull()).isTrue();
        assertThat(seenByOutsider.get("round").get("participantCount").asInt()).isEqualTo(6);
        assertThat(seenByOutsider.get("round").get("enrolled").asBoolean()).isFalse();
    }

    @Test
    @Tag("REQ-LUNCH-04")
    void nobodyHasGroupsBeforeTheRoundIsFormedNorWhenItWasCancelled() throws Exception {
        List<UUID> members = members(3);
        enrollAll(members);
        UUID open = UUID.fromString(call(HttpMethod.GET, "/lunch/rounds", admin, tenant, null).body().get("items").get(0).get("id").asText());

        JsonNode whileOpen = call(HttpMethod.GET, "/lunch/rounds/" + open, admin, tenant, null).body();
        assertThat(whileOpen.get("groups").isNull()).isTrue();
        assertThat(whileOpen.get("myGroup").isNull()).isTrue();
        assertThat(whileOpen.toString()).as("an open round does not say who is in").doesNotContain(members.get(0).toString());

        call(HttpMethod.POST, "/lunch/rounds/current/cancel", admin, tenant, null);
        JsonNode cancelled = call(HttpMethod.GET, "/lunch/rounds/" + open, members.get(0), tenant, null).body();
        assertThat(cancelled.get("round").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("myGroup").isNull()).isTrue();
    }

    @Test
    @Tag("REQ-LUNCH-04")
    void theListOfRoundsIsPagedNewestFirstWithCountsAndWhetherTheCallerWasIn() throws Exception {
        UUID regular = addMember(tenant, "USER", false);
        UUID skipper = addMember(tenant, "USER", false);
        for (int week = 0; week < 5; week++) {
            clock.setInstant(NOW.plus(Duration.ofDays(7L * week)));
            call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", regular, tenant, null);
            if (week != 2) {
                call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", skipper, tenant, null);
            }
            clock.setInstant(CUTOFF.plus(Duration.ofDays(7L * week)));
            jobs.formDueRounds();
        }

        List<String> weeks = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = call(HttpMethod.GET, "/lunch/rounds?size=2&page=" + page, skipper, tenant, null).body();
            assertThat(result.get("totalItems").asInt()).isEqualTo(5);
            result.get("items").forEach(item -> weeks.add(item.get("weekStart").asText() + ":" + item.get("participantCount").asInt() + ":" + item.get("enrolled").asBoolean()));
        }

        assertThat(weeks).containsExactly("2026-03-30:2:true", "2026-03-23:2:true", "2026-03-16:1:false", "2026-03-09:2:true", "2026-03-02:2:true");
        assertThat(call(HttpMethod.GET, "/lunch/rounds?size=2&page=3", skipper, tenant, null).body().get("items")).isEmpty();
        for (String bad : List.of("page=-1", "size=0", "size=101", "size=abc", "page=x")) {
            assertThat(call(HttpMethod.GET, "/lunch/rounds?" + bad, skipper, tenant, null).status()).as(bad).isEqualTo(400);
        }
        assertThat(call(HttpMethod.GET, "/lunch/rounds?size=100", skipper, tenant, null).status()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-LUNCH-04")
    void aRoundOfAnotherTenantOrThatDoesNotExistIsNotFound() throws Exception {
        enrollAll(members(2));
        UUID otherAdmin = UUID.randomUUID();
        UUID other = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", otherAdmin, other, Map.of("enabled", true));
        UUID mine = latestRoundId(admin);

        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + mine, otherAdmin, other, null).status()).as("A's id on B's path").isEqualTo(404);
        assertThat(callWithToken(HttpMethod.GET, tenant, "/lunch/rounds/" + mine, otherAdmin, other, null).status()).as("B's token on A's path").isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + UUID.randomUUID(), admin, tenant, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/lunch/rounds/not-an-id", admin, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/lunch/rounds/" + mine, UUID.randomUUID(), tenant, null).status()).as("a non-member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-LUNCH-04")
    void everyAnswerAboutLunchIsMarkedNotToBeCached() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        UUID round = latestRoundId(admin);

        for (String path : List.of("/lunch/config", "/lunch/rounds/current", "/lunch/rounds", "/lunch/rounds/" + round)) {
            MvcResult result = mockMvc.perform(identity(MockMvcRequestBuilders.get("/organizations/{t}" + path, tenant), member, tenant)).andReturn();
            assertThat(result.getResponse().getHeader("Cache-Control")).as(path).contains("no-store");
        }
    }

    // =====================================================================================================
    // not audited (REQ-LUNCH-05, REQ-MON-01)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-05")
    @Tag("REQ-MON-01")
    void roundsAndEnrolmentsAreNotAuditedActionsAndNothingForTheJournalIsPublishedForThem() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        addMember(tenant, "USER", false);
        List<UUID> members = members(4);
        config("FRIDAY", "10:00", 3);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);
        call(HttpMethod.DELETE, "/lunch/rounds/current/enrollment", member, tenant, null);
        enrollAll(members);
        call(HttpMethod.POST, "/lunch/rounds/current/cancel", admin, tenant, null);
        clock.setInstant(NOW.plus(Duration.ofDays(7)));
        enrollAll(members);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(7)).plus(Duration.ofDays(1)));
        jobs.formDueRounds();

        assertThat(ActivityEventCatalog.find("LUNCH_GROUPS_FORMED")).isEmpty();
        assertThat(ActivityEventCatalog.find("LUNCH_ROUND_CANCELLED")).isEmpty();
        assertThat(ActivityEventCatalog.eventTypes()).noneMatch(type -> type.startsWith("LUNCH"));
        assertThat(outboxTypes(tenant).stream().filter(type -> type.startsWith("LUNCH")).distinct().toList())
                .as("only the two notification events were published").containsExactlyInAnyOrder("LUNCH_ROUND_CANCELLED", "LUNCH_GROUPS_FORMED");
        assertThat(outboxTypes(tenant)).as("and neither is an audited action").noneMatch(type -> type.startsWith("LUNCH") && ActivityEventCatalog.find(type).isPresent());
    }

    // =====================================================================================================
    // retention and offboarding (REQ-LUNCH-05)
    // =====================================================================================================

    @Test
    @Tag("REQ-LUNCH-05")
    void roundsAndTheirGroupsAreKeptNinetyDaysAndThenDeletedInEveryTenant() throws Exception {
        List<UUID> members = members(4);
        enrollAll(members);
        closeFirstRound();
        UUID oldFormed = latestRoundId(admin);
        clock.setInstant(NOW.plus(Duration.ofDays(7)));
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", members.get(0), tenant, null);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(7)));
        jobs.formDueRounds(); // one participant: cancelled
        UUID otherAdmin = UUID.randomUUID();
        UUID other = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", otherAdmin, other, Map.of("enabled", true));
        UUID o1 = addMember(other, "USER", false);
        UUID o2 = addMember(other, "USER", false);
        clock.setInstant(NOW.plus(Duration.ofDays(14)));
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", o1, other, null);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", o2, other, null);
        clock.setInstant(CUTOFF.plus(Duration.ofDays(14)));
        jobs.formDueRounds();
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isEqualTo(2);

        clock.setInstant(CUTOFF.plus(Duration.ofDays(89)));
        assertThat(jobs.purgeOldRounds(Duration.ofDays(90))).as("nothing is 90 days old yet").isZero();
        clock.setInstant(CUTOFF.plus(Duration.ofDays(91)));
        int deleted = jobs.purgeOldRounds(Duration.ofDays(90));

        assertThat(deleted).as("the first week's round of the first tenant is over 90 days old").isGreaterThanOrEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds where id = ?", oldFormed)).isZero();
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where round_id = ?", oldFormed)).as("and its groups").isZero();
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).as("the second week's, one week younger, stays").isEqualTo(1);
        assertThat(rows(other, "select count(*) from org.lunch_rounds")).as("the other tenant's is two weeks younger").isEqualTo(1);

        clock.setInstant(CUTOFF.plus(Duration.ofDays(200)));
        jobs.purgeOldRounds(Duration.ofDays(90));
        assertThat(rows(tenant, "select count(*) from org.lunch_rounds")).isZero();
        assertThat(rows(other, "select count(*) from org.lunch_rounds")).isZero();
        assertThat(rows(other, "select count(*) from org.lunch_participants")).isZero();
        assertThat(jobs.purgeOldRounds(Duration.ofDays(90))).as("nothing left to delete").isZero();
    }

    @Test
    @Tag("REQ-LUNCH-05")
    void offboardingAMemberDeletesTheirParticipationsInThatTenantOnly() throws Exception {
        UUID leaving = addMember(tenant, "USER", false);
        List<UUID> others = members(3);
        enrollAll(others);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", leaving, tenant, null);
        closeFirstRound();
        clock.setInstant(NOW.plus(Duration.ofDays(7)));
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", leaving, tenant, null);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", others.get(0), tenant, null);
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenant = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", otherAdmin, otherTenant, Map.of("enabled", true));
        insertMembership(otherTenant, leaving, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", leaving, otherTenant, null);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", leaving)).isEqualTo(2);

        MvcResult offboarded = mockMvc.perform(identity(MockMvcRequestBuilders.delete("/organizations/{t}/members/{u}", tenant, leaving), admin, tenant)).andReturn();

        assertThat(offboarded.getResponse().getStatus()).isEqualTo(204);
        assertThat(rows(tenant, "select count(*) from org.lunch_participants where user_id = ?", leaving)).as("open and formed alike").isZero();
        assertThat(rows(tenant, "select count(*) from org.lunch_participants")).as("everybody else's stay").isEqualTo(4);
        assertThat(rows(otherTenant, "select count(*) from org.lunch_participants where user_id = ?", leaving)).as("the same person in another tenant").isEqualTo(1);
    }

    @Test
    @Tag("REQ-LUNCH-05")
    void theDatabaseRefusesToRemoveAMembershipThatStillHasParticipations() throws Exception {
        UUID member = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> update(tenant, "delete from org.memberships where user_id = ?", member)))
                .hasMessageContaining("violates foreign key constraint");
    }

    // =====================================================================================================
    // isolation (REQ-DATA-03)
    // =====================================================================================================

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-DATA-03")
    void oneTenantsRoundsAndGroupsAreInvisibleAndUntouchableFromAnother() throws Exception {
        List<UUID> membersA = members(4);
        enrollAll(membersA);
        UUID adminB = UUID.randomUUID();
        UUID tenantB = createOrganization(adminB);
        call(HttpMethod.PUT, "/modules/LUNCH_ROULETTE", adminB, tenantB, Map.of("enabled", true));
        List<UUID> membersB = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID member = addMember(tenantB, "USER", false);
            membersB.add(member);
            call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenantB, null);
        }

        clock.setInstant(CUTOFF.plusSeconds(60));
        jobs.formDueRounds();

        assertThat(call(HttpMethod.GET, "/lunch/rounds", adminB, tenantB, null).body().get("items")).hasSize(1);
        List<String> inB = groupsOf(tenantB, adminB, latestRoundId(tenantB, adminB)).stream().flatMap(List::stream).toList();
        assertThat(inB).containsExactlyInAnyOrderElementsOf(membersB.stream().map(UUID::toString).toList());
        for (UUID a : membersA) {
            assertThat(inB).doesNotContain(a.toString());
        }
        assertThat(call(HttpMethod.GET, "/lunch/rounds/current", membersA.get(0), tenant, null).body().get("participantCount").asInt()).as("the next week, empty").isZero();

        RlsAssertions rls = new RlsAssertions(dataSource);
        for (String table : List.of("org.lunch_rounds", "org.lunch_participants")) {
            rls.assertSeesOnlyOwnRows(table, tenant);
            rls.assertSeesOnlyOwnRows(table, tenantB);
            rls.assertNothingVisibleWithoutTenant(table);
        }
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    /** n new USER members of the current tenant. */
    private List<UUID> members(int n) throws Exception {
        List<UUID> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            members.add(addMember(tenant, "USER", false));
        }
        return members;
    }

    private void enrollAll(List<UUID> members) throws Exception {
        for (UUID member : members) {
            assertThat(call(HttpMethod.PUT, "/lunch/rounds/current/enrollment", member, tenant, null).status()).isEqualTo(200);
        }
    }

    /** Moves the clock a minute past the first round's cut-off and runs the job. */
    private void closeFirstRound() {
        clock.setInstant(CUTOFF.plusSeconds(60));
        jobs.formDueRounds();
    }

    private Resp config(String day, String cutoff, int size) throws Exception {
        return call(HttpMethod.PUT, "/lunch/config", admin, tenant, Map.of("day", day, "cutoff", cutoff, "groupSize", size));
    }

    private JsonNode current(UUID caller) throws Exception {
        Resp result = call(HttpMethod.GET, "/lunch/rounds/current", caller, tenant, null);
        assertThat(result.status()).isEqualTo(200);
        return result.body();
    }

    private UUID latestRoundId(UUID caller) throws Exception {
        return latestRoundId(tenant, caller);
    }

    private UUID latestRoundId(UUID inTenant, UUID caller) throws Exception {
        JsonNode items = call(HttpMethod.GET, "/lunch/rounds?size=1", caller, inTenant, null).body().get("items");
        assertThat(items).as("there is a round").hasSize(1);
        return UUID.fromString(items.get(0).get("id").asText());
    }

    private UUID roundIdOf(UUID inTenant) throws Exception {
        return UUID.fromString(inTenantSession(inTenant, jdbc -> jdbc.queryForObject("select id::text from org.lunch_rounds order by week_start desc limit 1", String.class)));
    }

    private String roundStatus(UUID inTenant) throws Exception {
        return inTenantSession(inTenant, jdbc -> jdbc.queryForObject("select status from org.lunch_rounds order by week_start desc limit 1", String.class));
    }

    /** The groups of a formed round as an approver sees them, each a list of user ids. */
    private List<List<String>> groupsOf(UUID caller, UUID roundId) throws Exception {
        return groupsOf(tenant, caller, roundId);
    }

    private List<List<String>> groupsOf(UUID inTenant, UUID caller, UUID roundId) throws Exception {
        JsonNode groups = call(HttpMethod.GET, "/lunch/rounds/" + roundId, caller, inTenant, null).body().get("groups");
        List<List<String>> result = new ArrayList<>();
        groups.forEach(group -> result.add(texts(group)));
        return result;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private Resp call(HttpMethod method, String path, UUID caller, UUID inTenant, Object body) throws Exception {
        return callWithToken(method, inTenant, path, caller, inTenant, body);
    }

    private Resp callWithBody(HttpMethod method, String path, UUID caller, UUID inTenant, String body) throws Exception {
        return callWithToken(method, inTenant, path, caller, inTenant, body);
    }

    /** The path names {@code pathTenant}; the identity headers are the token's: {@code caller} in {@code tokenTenant}. */
    private Resp callWithToken(HttpMethod method, UUID pathTenant, String path, UUID caller, UUID tokenTenant, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, "/organizations/" + pathTenant + path)
                .header("X-User-Id", caller.toString()).header("X-Tenant-Id", tokenTenant.toString());
        if (body != null) {
            request.contentType("application/json").content(body instanceof String s ? s : objectMapper.writeValueAsString(body));
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String text = result.getResponse().getContentAsString();
        return new Resp(result.getResponse().getStatus(), text.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(text));
    }

    private static MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request, UUID caller, UUID inTenant) {
        return request.header("X-User-Id", caller.toString()).header("X-Tenant-Id", inTenant.toString());
    }

    private UUID createOrganization(UUID creator) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/organizations").contentType("application/json")
                .header("X-User-Id", creator.toString()).content("{\"name\":\"Lunch Co\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private UUID addMember(UUID inTenant, String role, boolean readOnly) throws Exception {
        UUID user = UUID.randomUUID();
        insertMembership(inTenant, user, role, readOnly);
        return user;
    }

    private void insertMembership(UUID inTenant, UUID user, String role, boolean readOnly) throws Exception {
        update(inTenant, "insert into org.memberships (id, tenant_id, user_id, role, read_only) values (?, ?, ?, ?, ?)",
                UUID.randomUUID(), inTenant, user, role, readOnly);
    }

    private long rows(UUID inTenant, String sql, Object... args) throws Exception {
        Long count = inTenantSession(inTenant, jdbc -> jdbc.queryForObject(sql, Long.class, args));
        return count == null ? 0 : count;
    }

    private void update(UUID inTenant, String sql, Object... args) throws Exception {
        inTenantSession(inTenant, jdbc -> jdbc.update(sql, args));
    }

    /**
     * Runs work on one connection whose session has the tenant set, then clears it: the setting is session-level, and a
     * connection that went back to the pool still carrying it would make the RLS assertions see that tenant's rows.
     */
    private <T> T inTenantSession(UUID inTenant, java.util.function.Function<JdbcTemplate, T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("select set_config('app.tenant_id', '" + inTenant + "', false)");
            try {
                return work.apply(jdbc);
            } finally {
                jdbc.execute("select set_config('app.tenant_id', '', false)");
            }
        }
    }

    /** The payloads of one event type this tenant's outbox holds, oldest first. */
    private List<JsonNode> events(UUID inTenant, String type) throws Exception {
        List<JsonNode> payloads = new ArrayList<>();
        for (String json : outboxPayloads(inTenant, "select payload from org.outbox where tenant_id = ? and event_type = ? order by created_at, id", inTenant, type)) {
            payloads.add(objectMapper.readTree(json).get("payload"));
        }
        return payloads;
    }

    private String allOutbox(UUID inTenant) throws Exception {
        return String.join("\n", outboxPayloads(inTenant, "select payload from org.outbox where tenant_id = ?", inTenant));
    }

    private List<String> outboxTypes(UUID inTenant) throws Exception {
        return outboxPayloads(inTenant, "select event_type from org.outbox where tenant_id = ? order by created_at, id", inTenant);
    }

    private List<String> outboxChannels(UUID inTenant, String type) throws Exception {
        return inTenantSession(inTenant, jdbc -> jdbc.queryForList(
                "select destination || ':' || channel from org.outbox where tenant_id = ? and event_type = ?", String.class, inTenant, type));
    }

    private List<String> outboxPayloads(UUID inTenant, String sql, Object... args) throws Exception {
        return inTenantSession(inTenant, jdbc -> jdbc.queryForList(sql, String.class, args));
    }

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock clock() {
            return MutableClock.at(NOW);
        }

        /** Seeded, so a run is reproducible; the tests assert properties that hold for any seed. */
        @Bean
        @Primary
        Random seededLunchRandom() {
            return new Random(20260302L);
        }
    }
}
