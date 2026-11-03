package com.saas.org;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The business-module switch and the leave module (REQ-MOD-01, REQ-LEAVE-01..08, REQ-MON-01, REQ-NOTIF-03) through the
 * real HTTP layer, with the identity headers the gateway sets from the token, against the real topology (so the service's
 * own non-superuser role and RLS apply). Time is a {@link MutableClock} fixed to Monday 2026-03-02, billing-service is
 * a stub whose answer the test changes, and what the module tells the other services is read from the outbox.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "test.marker=LeaveModuleIT"
})
@AutoConfigureMockMvc
class LeaveModuleIT {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static volatile String accessStatus = "ACTIVE";
    private static final HttpServer billingStub = startBillingStub();

    /** A Monday, 10:00 in Bucharest. */
    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MutableClock clock;

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
        assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, Map.of("enabled", true)).status()).isEqualTo(200);
    }

    // =====================================================================================================
    // the module switch and the time zone (REQ-MOD-01)
    // =====================================================================================================

    @Test
    @Tag("REQ-MOD-01")
    void modulesAreOffByDefaultAndAnyMemberSeesWhichAreOnAndTheTimeZone() throws Exception {
        UUID fresh = UUID.randomUUID();
        UUID freshTenant = createOrganization(fresh);
        UUID user = addMember(freshTenant, "USER", false);

        for (UUID caller : List.of(fresh, user)) {
            Resp view = call(HttpMethod.GET, "/modules", caller, freshTenant, null);
            assertThat(view.status()).isEqualTo(200);
            assertThat(view.body().get("modules").get("LEAVE").asBoolean()).isFalse();
            assertThat(view.body().get("timezone").asText()).isEqualTo("Europe/Bucharest");
        }
        assertThat(call(HttpMethod.GET, "/modules", UUID.randomUUID(), freshTenant, null).status()).as("not a member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-MOD-01")
    void aModuleThatIsOffDoesNotExistForTheTenant() throws Exception {
        UUID offAdmin = UUID.randomUUID();
        UUID off = createOrganization(offAdmin);
        UUID user = addMember(off, "USER", false);
        UUID someRequest = UUID.randomUUID();
        String create = "{\"type\":\"ODIHNA\",\"startDate\":\"2026-03-09\",\"endDate\":\"2026-03-10\"}";

        for (UUID caller : List.of(offAdmin, user)) {
            assertThat(call(HttpMethod.POST, "/leave/requests", caller, off, create).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/requests", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/requests/" + someRequest, caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + someRequest + "/approve", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + someRequest + "/reject", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + someRequest + "/cancel", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/balance", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-31", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/policy", caller, off, null).status()).isEqualTo(404);
            assertThat(call(HttpMethod.GET, "/leave/holidays?year=2026", caller, off, null).status()).isEqualTo(404);
        }
        assertThat(call(HttpMethod.PUT, "/leave/policy", offAdmin, off, Map.of("allowance", 10)).status()).isEqualTo(404);
        assertThat(call(HttpMethod.PUT, "/leave/balances/" + user, offAdmin, off, Map.of("allowance", 10)).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/leave/holidays", offAdmin, off, Map.of("date", "2026-12-25", "name", "Christmas")).status()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE, "/leave/holidays/2026-12-25", offAdmin, off, null).status()).isEqualTo(404);
        assertThat(rows(off, "select count(*) from org.leave_requests")).isZero();
    }

    @Test
    @Tag("REQ-MOD-01")
    void onlyAnAdminWithWriteAccessSwitchesAModule() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID user = addMember(tenant, "USER", false);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);
        UUID nobody = UUID.randomUUID();

        for (UUID caller : List.of(manager, user, readOnlyAdmin, nobody)) {
            assertThat(call(HttpMethod.PUT, "/modules/LEAVE", caller, tenant, Map.of("enabled", false)).status()).as(caller.toString()).isEqualTo(403);
        }
        assertThat(call(HttpMethod.GET, "/modules", user, tenant, null).body().get("modules").get("LEAVE").asBoolean()).isTrue();
        assertThat(call(HttpMethod.PUT, "/modules/LUNCH", admin, tenant, Map.of("enabled", true)).status()).as("a module that does not exist").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/modules/leave", admin, tenant, Map.of("enabled", true)).status()).as("names are exact").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, "{}").status()).as("no flag").isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, "{\"enabled\":\"maybe\"}").status()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-MON-01")
    void switchingAModuleIsJournaledAsAnAuditedEventOnlyWhenItChangesTheSetting() throws Exception {
        UUID freshAdmin = UUID.randomUUID();
        UUID fresh = createOrganization(freshAdmin);

        call(HttpMethod.PUT, "/modules/LEAVE", freshAdmin, fresh, Map.of("enabled", false)); // already off: nothing
        call(HttpMethod.PUT, "/modules/LEAVE", freshAdmin, fresh, Map.of("enabled", true));
        call(HttpMethod.PUT, "/modules/LEAVE", freshAdmin, fresh, Map.of("enabled", true)); // already on: nothing
        call(HttpMethod.PUT, "/modules/LEAVE", freshAdmin, fresh, Map.of("enabled", false));

        List<JsonNode> events = events(fresh, "MODULE_CHANGED");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("enabled").asBoolean()).isTrue();
        assertThat(events.get(1).get("enabled").asBoolean()).isFalse();
        for (JsonNode event : events) {
            assertThat(event.get("module").asText()).isEqualTo("LEAVE");
            assertThatMatchesTheJournalCatalog("MODULE_CHANGED", event, freshAdmin);
        }
    }

    @Test
    @Tag("REQ-MOD-01")
    void switchingTheModuleOffKeepsItsDataAndSwitchingItOnBringsItBack() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID another = addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 1)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + id + "/approve", another, tenant, null);
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "Christmas"));
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 12));

        assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/leave/requests", user, tenant, null).status()).isEqualTo(404);
        assertThat(rows(tenant, "select count(*) from org.leave_requests")).as("kept while it is off").isEqualTo(1);

        assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, Map.of("enabled", true)).status()).isEqualTo(200);
        Resp mine = call(HttpMethod.GET, "/leave/requests", user, tenant, null);
        assertThat(mine.body().get("items")).hasSize(1);
        assertThat(mine.body().get("items").get(0).get("id").asText()).isEqualTo(id);
        assertThat(mine.body().get("items").get(0).get("status").asText()).isEqualTo("APPROVED");
        assertThat(call(HttpMethod.GET, "/leave/policy", user, tenant, null).body().get("defaultAllowance").asInt()).isEqualTo(12);
        assertThat(call(HttpMethod.GET, "/leave/holidays?year=2026", user, tenant, null).body()).hasSize(1);
        assertThat(call(HttpMethod.GET, "/leave/balance", user, tenant, null).body().get("approved").asInt()).isEqualTo(2);
    }

    @Test
    @Tag("REQ-MOD-01")
    void theTimeZoneIsEuropeBucharestByDefaultAndOnlyAnAdminChangesIt() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);

        for (UUID caller : List.of(manager, readOnlyAdmin, UUID.randomUUID())) {
            assertThat(call(HttpMethod.PUT, "/timezone", caller, tenant, Map.of("timezone", "America/New_York")).status()).isEqualTo(403);
        }
        assertThat(call(HttpMethod.GET, "/modules", manager, tenant, null).body().get("timezone").asText()).isEqualTo("Europe/Bucharest");

        Resp changed = call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", "America/New_York"));

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().get("timezone").asText()).isEqualTo("America/New_York");
        assertThat(call(HttpMethod.GET, "/modules", manager, tenant, null).body().get("timezone").asText()).isEqualTo("America/New_York");
    }

    @ParameterizedTest(name = "time zone [{0}] is refused")
    @ValueSource(strings = {"", " ", "Mars/Phobos", "+02:00", "GMT+2", "europe/bucharest", "Europe/Bucharest ", "../etc/passwd", "Europe/Bucharest;drop table x"})
    @Tag("REQ-MOD-01")
    void aTimeZoneThatIsNotARegionNameIsRefused(String zone) throws Exception {
        assertThat(call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", zone)).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/modules", admin, tenant, null).body().get("timezone").asText()).isEqualTo("Europe/Bucharest");
    }

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-LEAVE-04")
    void theTenantsTimeZoneDecidesWhichDayItIsTodayForTheStartOfARequest() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        clock.setInstant(Instant.parse("2026-03-02T22:30:00Z")); // already Tuesday 00:30 in Bucharest, still Monday 17:30 in New York

        Resp inBucharest = create(user, "FARA_PLATA", TODAY, TODAY);
        call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", "America/New_York"));
        Resp inNewYork = create(user, "FARA_PLATA", TODAY, TODAY);

        assertThat(inBucharest.status()).as("Monday is already in the past in Bucharest").isEqualTo(400);
        assertThat(inNewYork.status()).as("Monday is still today in New York").isEqualTo(201);
    }

    @Test
    @Tag("REQ-MOD-01")
    void whileTheTenantIsInItsGracePeriodOrSuspendedNoWriteOfTheModuleIsAccepted() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String pending = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();

        for (String blocked : List.of("READ_ONLY", "SUSPENDED")) {
            accessStatus = blocked;
            assertThat(call(HttpMethod.PUT, "/modules/LEAVE", admin, tenant, Map.of("enabled", false)).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.PUT, "/timezone", admin, tenant, Map.of("timezone", "UTC")).status()).as(blocked).isEqualTo(403);
            assertThat(create(user, "ODIHNA", week(2, 0), week(2, 0)).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + pending + "/approve", manager, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + pending + "/reject", manager, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/leave/requests/" + pending + "/cancel", user, tenant, null).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 5)).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.PUT, "/leave/balances/" + user, admin, tenant, Map.of("allowance", 5)).status()).as(blocked).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "X")).status()).as(blocked).isEqualTo(403);

            assertThat(call(HttpMethod.GET, "/leave/requests", user, tenant, null).status()).as("reads stay open: " + blocked).isEqualTo(200);
            assertThat(call(HttpMethod.GET, "/leave/balance", user, tenant, null).status()).isEqualTo(200);
            assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-31", user, tenant, null).status()).isEqualTo(200);
        }
        assertThat(call(HttpMethod.GET, "/leave/requests/" + pending, user, tenant, null).body().get("status").asText()).isEqualTo("PENDING");

        accessStatus = "ACTIVE";
        assertThat(call(HttpMethod.POST, "/leave/requests/" + pending + "/approve", manager, tenant, null).status()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-MOD-01")
    void theReadOnlyFlagBlocksEveryWriteOfTheModuleEvenAboutTheMembersOwnRequests() throws Exception {
        UUID readOnlyUser = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String own = create(readOnlyUser, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();
        String approvedOwn = create(readOnlyUser, "ODIHNA", week(2, 0), week(2, 0)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + approvedOwn + "/approve", manager, tenant, null);
        update(tenant, "update org.memberships set read_only = true where user_id = ?", readOnlyUser);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);

        assertThat(create(readOnlyUser, "ODIHNA", week(3, 0), week(3, 0)).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + own + "/cancel", readOnlyUser, tenant, null).status()).as("cancel own waiting").isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + approvedOwn + "/cancel", readOnlyUser, tenant, null).status()).as("cancel own approved").isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + own + "/approve", readOnlyManager, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + own + "/reject", readOnlyManager, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + approvedOwn + "/cancel", readOnlyManager, tenant, null).status()).isEqualTo(403);

        assertThat(call(HttpMethod.GET, "/leave/requests", readOnlyUser, tenant, null).body().get("items")).as("they still read their own").hasSize(2);
        assertThat(call(HttpMethod.GET, "/leave/balance", readOnlyUser, tenant, null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-31", readOnlyUser, tenant, null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=ALL", readOnlyManager, tenant, null).body().get("items")).as("approvers read all even read-only").hasSize(2);
    }

    // =====================================================================================================
    // making a request (REQ-LEAVE-01)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-01")
    void aMemberRequestsLeaveForThemselvesAndItWaitsForADecision() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        Resp created = call(HttpMethod.POST, "/leave/requests", user, tenant,
                Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(), "endDate", week(1, 4).toString(), "reason", "  Family trip  "));

        assertThat(created.status()).isEqualTo(201);
        JsonNode body = created.body();
        assertThat(body.get("userId").asText()).isEqualTo(user.toString());
        assertThat(body.get("type").asText()).isEqualTo("ODIHNA");
        assertThat(body.get("startDate").asText()).isEqualTo(week(1, 0).toString());
        assertThat(body.get("endDate").asText()).isEqualTo(week(1, 4).toString());
        assertThat(body.get("days").asInt()).isEqualTo(5);
        assertThat(body.get("reason").asText()).isEqualTo("Family trip");
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("autoApproved").asBoolean()).isFalse();
        assertThat(body.get("decidedBy").isNull()).isTrue();
        assertThat(body.get("id").asText()).isNotBlank();
    }

    @Test
    @Tag("REQ-LEAVE-01")
    void bothKindsOfLeaveAreAccepted() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        assertThat(create(user, "ODIHNA", week(1, 0), week(1, 0)).status()).isEqualTo(201);
        assertThat(create(user, "FARA_PLATA", week(2, 0), week(2, 0)).status()).isEqualTo(201);
        assertThat(create(user, "MEDICAL", week(3, 0), week(3, 0)).status()).as("there is no sick leave in this version").isEqualTo(400);
        assertThat(create(user, "odihna", week(3, 0), week(3, 0)).status()).as("exact names").isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-01")
    @Tag("REQ-AUTH-05")
    void aRequestIsAlwaysForTheCallerWhateverTheBodySays() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID victim = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        Resp created = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "ODIHNA", "userId", victim.toString(),
                "user_id", victim.toString(), "tenantId", UUID.randomUUID().toString(), "status", "APPROVED",
                "startDate", week(1, 0).toString(), "endDate", week(1, 0).toString()));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("userId").asText()).isEqualTo(user.toString());
        assertThat(created.body().get("status").asText()).as("a status in the body is not honoured").isEqualTo("PENDING");
        assertThat(rows(tenant, "select count(*) from org.leave_requests where user_id = ?", victim)).isZero();
    }

    @Test
    @Tag("REQ-LEAVE-01")
    void aRequestStartingTodayIsAcceptedButOneStartingYesterdayIsNot() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        assertThat(create(user, "FARA_PLATA", TODAY, TODAY).status()).isEqualTo(201);
        assertThat(create(user, "FARA_PLATA", TODAY.minusDays(3), TODAY.minusDays(3)).status()).isEqualTo(400); // Friday
    }

    @Test
    @Tag("REQ-LEAVE-01")
    void aRequestMustBeWholeDaysOfOneCalendarYearInOrder() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String type = "\"type\":\"FARA_PLATA\"";

        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"startDate\":\"2026-03-10\",\"endDate\":\"2026-03-09\"}").status())
                .as("end before start").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"startDate\":\"2026-12-28\",\"endDate\":\"2027-01-05\"}").status())
                .as("across a year boundary").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"startDate\":\"2026-03-09T09:00:00\",\"endDate\":\"2026-03-10\"}").status())
                .as("a time is not a day: no half days").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"startDate\":\"tomorrow\",\"endDate\":\"2026-03-10\"}").status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"endDate\":\"2026-03-10\"}").status()).as("no start").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{\"startDate\":\"2026-03-09\",\"endDate\":\"2026-03-10\"}").status()).as("no type").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{" + type + ",\"startDate\":\"9999-03-09\",\"endDate\":\"9999-03-10\"}").status())
                .as("a year nobody means").isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests", user, tenant, "{not json").status()).isEqualTo(400);
        assertThat(rows(tenant, "select count(*) from org.leave_requests")).isZero();
        assertThat(create(user, "FARA_PLATA", LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 31)).status()).as("up to the last day of the year").isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-01")
    void theReasonIsOptionalAtMost500CharactersAndBlankMeansNone() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        Resp none = create(user, "FARA_PLATA", week(1, 0), week(1, 0));
        Resp blank = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "FARA_PLATA", "startDate", week(2, 0).toString(),
                "endDate", week(2, 0).toString(), "reason", "   "));
        Resp longest = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "FARA_PLATA", "startDate", week(3, 0).toString(),
                "endDate", week(3, 0).toString(), "reason", "r".repeat(500)));
        Resp tooLong = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "FARA_PLATA", "startDate", week(4, 0).toString(),
                "endDate", week(4, 0).toString(), "reason", "r".repeat(501)));

        assertThat(none.status()).isEqualTo(201);
        assertThat(none.body().get("reason").isNull()).isTrue();
        assertThat(blank.body().get("reason").isNull()).isTrue();
        assertThat(longest.body().get("reason").asText()).hasSize(500);
        assertThat(tooLong.status()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-01")
    void aMemberOfAnotherOrganizationAndANonMemberCannotRequest() throws Exception {
        UUID outsider = UUID.randomUUID();
        UUID otherTenant = createOrganization(outsider);

        assertThat(create(outsider, "FARA_PLATA", week(1, 0), week(1, 0)).status()).as("their own tenant's token, our path").isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests", UUID.randomUUID(), tenant, Map.of("type", "FARA_PLATA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 0).toString())).status()).as("not a member").isEqualTo(403);
        assertThat(callWithToken(HttpMethod.POST, tenant, "/leave/requests", outsider, otherTenant, Map.of("type", "FARA_PLATA",
                "startDate", week(1, 0).toString(), "endDate", week(1, 0).toString())).status()).as("token of tenant B on tenant A's path").isEqualTo(403);
        assertThat(rows(tenant, "select count(*) from org.leave_requests")).isZero();
    }

    // =====================================================================================================
    // counting days (REQ-LEAVE-02)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-02")
    void onlyWorkingDaysAreCountedSoAWeekendInsideTheIntervalCostsNothing() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        assertThat(create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("days").asInt()).as("Mon-Fri").isEqualTo(5);
        assertThat(create(user, "ODIHNA", week(2, 3), week(3, 1)).body().get("days").asInt()).as("Thu-Tue over a weekend").isEqualTo(4);
        assertThat(create(user, "ODIHNA", week(4, 0), week(4, 6)).body().get("days").asInt()).as("Mon-Sun").isEqualTo(5);
    }

    @Test
    @Tag("REQ-LEAVE-02")
    void anIntervalOfOnlyAWeekendHasNoWorkingDayAndIsRefused() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        Resp weekend = create(user, "ODIHNA", week(1, 5), week(1, 6));

        assertThat(weekend.status()).isEqualTo(400);
        assertThat(weekend.body().get("message").asText()).contains("no working day");
    }

    @Test
    @Tag("REQ-LEAVE-02")
    void theHolidaysTheAdminListedAreNotCounted() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", week(1, 2).toString(), "name", "Mid-week holiday"));
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", week(1, 4).toString(), "name", "Friday holiday"));
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", week(1, 5).toString(), "name", "Saturday holiday"));

        Resp request = create(user, "ODIHNA", week(1, 0), week(1, 6));
        Resp onlyAHoliday = create(user, "ODIHNA", week(1, 2), week(1, 2));

        assertThat(request.body().get("days").asInt()).as("5 weekdays - 2 holidays; the Saturday one changes nothing").isEqualTo(3);
        assertThat(onlyAHoliday.status()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-02")
    void aHolidayAddedLaterDoesNotChangeARequestThatWasAlreadyCounted() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();

        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", week(1, 1).toString(), "name", "Late holiday"));

        assertThat(call(HttpMethod.GET, "/leave/requests/" + id, user, tenant, null).body().get("days").asInt()).isEqualTo(5);
        assertThat(create(user, "ODIHNA", week(2, 0), week(2, 4)).body().get("days").asInt()).isEqualTo(5);
    }

    @Test
    @Tag("REQ-LEAVE-02")
    void anotherTenantsHolidaysDoNotCount() throws Exception {
        UUID otherAdmin = UUID.randomUUID();
        UUID other = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LEAVE", otherAdmin, other, Map.of("enabled", true));
        call(HttpMethod.POST, "/leave/holidays", otherAdmin, other, Map.of("date", week(1, 1).toString(), "name", "Theirs"));
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        assertThat(create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("days").asInt()).isEqualTo(5);
    }

    // =====================================================================================================
    // the balance (REQ-LEAVE-03)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-03")
    void theDefaultYearlyAllowanceIs21AndTheBalanceIsAllocatedMinusApprovedMinusPending() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);

        assertThat(balance(user, null)).containsExactly(2026, 21, 0, 0, 21);

        String approved = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();
        create(user, "ODIHNA", week(2, 0), week(2, 2));
        assertThat(balance(user, null)).as("5 pending, then 3 more").containsExactly(2026, 21, 0, 8, 13);

        call(HttpMethod.POST, "/leave/requests/" + approved + "/approve", manager, tenant, null);
        assertThat(balance(user, null)).as("5 of them now approved").containsExactly(2026, 21, 5, 3, 13);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void onlyRestLeaveDrawsOnTheAllowance() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);

        assertThat(create(user, "FARA_PLATA", week(1, 0), week(1, 4)).status()).isEqualTo(201);

        assertThat(balance(user, null)).containsExactly(2026, 21, 0, 0, 21);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 0));
        assertThat(create(user, "FARA_PLATA", week(2, 0), week(2, 4)).status()).as("unpaid leave needs no allowance").isEqualTo(201);
        assertThat(create(user, "ODIHNA", week(3, 0), week(3, 0)).status()).as("rest leave with a zero allowance").isEqualTo(409);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void aRequestLongerThanWhatIsLeftIsRefusedAtCreationAndOneThatFitsExactlyIsAccepted() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 7));

        Resp tooLong = create(user, "ODIHNA", week(1, 0), week(2, 1)); // 7 working days... Mon-Fri + Mon-Tue = 7
        assertThat(tooLong.status()).as("exactly the allowance fits").isEqualTo(201);
        Resp oneMore = create(user, "ODIHNA", week(3, 0), week(3, 0));

        assertThat(oneMore.status()).isEqualTo(409);
        assertThat(oneMore.body().get("message").asText()).contains("not enough").contains("0 available");
        assertThat(balance(user, null)).containsExactly(2026, 7, 0, 7, 0);
        UUID other = addMember(tenant, "USER", false);
        assertThat(create(other, "ODIHNA", week(1, 0), week(2, 1)).status()).as("another member has their own allowance").isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void aWaitingRequestKeepsItsDaysReservedAndARejectionOrCancellationGivesThemBack() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 5));
        String first = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();

        assertThat(create(user, "ODIHNA", week(2, 0), week(2, 0)).status()).as("reserved by the waiting request").isEqualTo(409);

        call(HttpMethod.POST, "/leave/requests/" + first + "/reject", manager, tenant, null);
        assertThat(balance(user, null)).as("a rejection frees them").containsExactly(2026, 5, 0, 0, 5);
        String second = create(user, "ODIHNA", week(2, 0), week(2, 4)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + second + "/approve", manager, tenant, null);
        assertThat(create(user, "ODIHNA", week(3, 0), week(3, 0)).status()).as("approved days are used").isEqualTo(409);

        call(HttpMethod.POST, "/leave/requests/" + second + "/cancel", user, tenant, null);
        assertThat(balance(user, null)).as("cancelling an approved leave gives the days back").containsExactly(2026, 5, 0, 0, 5);
        assertThat(create(user, "ODIHNA", week(3, 0), week(3, 4)).status()).isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void theBalanceIsPerCalendarYear() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 5));
        create(user, "ODIHNA", week(1, 0), week(1, 4));

        assertThat(balance(user, 2026)).containsExactly(2026, 5, 0, 5, 0);
        assertThat(balance(user, 2027)).as("nothing taken in 2027").containsExactly(2027, 5, 0, 0, 5);
        assertThat(create(user, "ODIHNA", LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 8)).status()).isEqualTo(201);
        assertThat(call(HttpMethod.GET, "/leave/balance?year=1999", user, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/balance?year=abc", user, tenant, null).status()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void anAdminSetsTheDefaultAllowanceAndAnOverrideForOneMemberWhichBeatsIt() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID favoured = addMember(tenant, "USER", false);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 10));

        assertThat(call(HttpMethod.PUT, "/leave/balances/" + favoured, admin, tenant, Map.of("allowance", 30)).status()).isEqualTo(204);

        assertThat(balance(user, null)).containsExactly(2026, 10, 0, 0, 10);
        assertThat(balance(favoured, null)).containsExactly(2026, 30, 0, 0, 30);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 12));
        assertThat(balance(favoured, null)).as("the default does not touch an override").containsExactly(2026, 30, 0, 0, 30);
        assertThat(balance(user, null)).containsExactly(2026, 12, 0, 0, 12);

        assertThat(call(HttpMethod.DELETE, "/leave/balances/" + favoured, admin, tenant, null).status()).isEqualTo(204);
        assertThat(balance(favoured, null)).as("back to the default").containsExactly(2026, 12, 0, 0, 12);
        assertThat(call(HttpMethod.DELETE, "/leave/balances/" + favoured, admin, tenant, null).status()).as("removing nothing is not an error").isEqualTo(204);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void loweringTheAllowanceBelowWhatIsTakenLeavesTheBalanceNegativeAndBlocksNewRequests() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        create(user, "ODIHNA", week(1, 0), week(1, 4));

        call(HttpMethod.PUT, "/leave/balances/" + user, admin, tenant, Map.of("allowance", 3));

        assertThat(balance(user, null)).containsExactly(2026, 3, 0, 5, -2);
        assertThat(create(user, "ODIHNA", week(2, 0), week(2, 0)).status()).isEqualTo(409);
        assertThat(call(HttpMethod.GET, "/leave/requests", user, tenant, null).body().get("items")).as("what was granted stays").hasSize(1);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void aMemberSeesOnlyTheirOwnBalanceAndApproversSeeAnyMembers() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID other = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/leave/balances/" + other, admin, tenant, Map.of("allowance", 4));

        assertThat(call(HttpMethod.GET, "/leave/balance?userId=" + other, user, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/balance?userId=" + user, user, tenant, null).status()).as("their own, named").isEqualTo(200);
        for (UUID approver : List.of(manager, admin)) {
            Resp theirs = call(HttpMethod.GET, "/leave/balance?userId=" + other, approver, tenant, null);
            assertThat(theirs.status()).isEqualTo(200);
            assertThat(theirs.body().get("userId").asText()).isEqualTo(other.toString());
            assertThat(theirs.body().get("allocated").asInt()).isEqualTo(4);
        }
        assertThat(call(HttpMethod.GET, "/leave/balance?userId=" + UUID.randomUUID(), manager, tenant, null).status()).as("not a member").isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void theLeavePolicyIsSetOnlyByAnAdminWithWriteAccessWithinSaneBounds() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID user = addMember(tenant, "USER", false);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);

        for (UUID caller : List.of(manager, user, readOnlyAdmin, UUID.randomUUID())) {
            assertThat(call(HttpMethod.PUT, "/leave/policy", caller, tenant, Map.of("allowance", 10)).status()).as("default by " + caller).isEqualTo(403);
            assertThat(call(HttpMethod.PUT, "/leave/balances/" + user, caller, tenant, Map.of("allowance", 10)).status()).as("override by " + caller).isEqualTo(403);
            assertThat(call(HttpMethod.POST, "/leave/holidays", caller, tenant, Map.of("date", "2026-12-25", "name", "X")).status()).as("holiday by " + caller).isEqualTo(403);
            assertThat(call(HttpMethod.DELETE, "/leave/holidays/2026-12-25", caller, tenant, null).status()).isEqualTo(403);
        }
        for (Object bad : List.of(-1, 367, 100000)) {
            assertThat(call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", bad)).status()).as("allowance " + bad).isEqualTo(400);
            assertThat(call(HttpMethod.PUT, "/leave/balances/" + user, admin, tenant, Map.of("allowance", bad)).status()).isEqualTo(400);
        }
        assertThat(call(HttpMethod.PUT, "/leave/policy", admin, tenant, "{}").status()).isEqualTo(400);
        assertThat(call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 0)).status()).as("zero is allowed").isEqualTo(200);
        assertThat(call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 366)).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, "/leave/balances/" + UUID.randomUUID(), admin, tenant, Map.of("allowance", 10)).status()).as("not a member").isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/policy", user, tenant, null).body().get("defaultAllowance").asInt()).as("any member reads it").isEqualTo(366);
    }

    @Test
    @Tag("REQ-LEAVE-02")
    void theHolidayListIsKeptByTheAdminAndReadByEveryMember() throws Exception {
        UUID user = addMember(tenant, "USER", false);

        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "  Christmas  ")).status()).isEqualTo(201);
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-01-01", "name", "New Year"));
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2027-01-01", "name", "Next year"));

        Resp list = call(HttpMethod.GET, "/leave/holidays?year=2026", user, tenant, null);
        assertThat(list.body()).hasSize(2);
        assertThat(list.body().get(0).get("date").asText()).isEqualTo("2026-01-01");
        assertThat(list.body().get(1).get("name").asText()).isEqualTo("Christmas");
        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "Again")).status()).as("a day is a holiday once").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-26", "name", " ")).status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-26", "name", "n".repeat(101))).status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, "{\"name\":\"No date\"}").status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "1999-12-26", "name", "Too old")).status()).isEqualTo(400);

        assertThat(call(HttpMethod.DELETE, "/leave/holidays/2026-12-25", admin, tenant, null).status()).isEqualTo(204);
        assertThat(call(HttpMethod.DELETE, "/leave/holidays/2026-12-25", admin, tenant, null).status()).as("already gone").isEqualTo(400);
        assertThat(call(HttpMethod.DELETE, "/leave/holidays/not-a-date", admin, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/holidays?year=2026", user, tenant, null).body()).hasSize(1);
        assertThat(call(HttpMethod.GET, "/leave/holidays?year=1800", user, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/holidays", user, tenant, null).status()).as("the year is required").isEqualTo(400);
    }

    // =====================================================================================================
    // overlaps (REQ-LEAVE-03)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-03")
    void aRequestOverlappingAWaitingOrApprovedOneOfTheSameMemberIsRefused() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String waiting = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();

        assertThat(create(user, "FARA_PLATA", week(1, 2), week(1, 2)).status()).as("inside, another kind").isEqualTo(409);
        assertThat(create(user, "ODIHNA", week(1, 4), week(2, 1)).status()).as("shares the last day").isEqualTo(409);
        assertThat(create(user, "ODIHNA", week(0, 4), week(1, 0)).status()).as("shares the first day").isEqualTo(409);
        assertThat(create(user, "ODIHNA", week(0, 0), week(2, 4)).status()).as("contains it").isEqualTo(409);

        call(HttpMethod.POST, "/leave/requests/" + waiting + "/approve", manager, tenant, null);
        assertThat(create(user, "FARA_PLATA", week(1, 1), week(1, 3)).status()).as("overlapping an approved one").isEqualTo(409);

        assertThat(create(user, "ODIHNA", week(2, 0), week(2, 0)).status()).as("the next week is free").isEqualTo(201);
        assertThat(create(user, "ODIHNA", week(1, 5), week(1, 6)).status()).as("a weekend with no working day is refused for that, not as an overlap").isEqualTo(400);
        UUID other = addMember(tenant, "USER", false);
        assertThat(create(other, "ODIHNA", week(1, 0), week(1, 4)).status()).as("another member may take the same days").isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void aRejectedOrCancelledRequestNoLongerBlocksItsDays() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String rejected = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + rejected + "/reject", manager, tenant, null);
        String retried = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + retried + "/cancel", user, tenant, null);

        assertThat(create(user, "ODIHNA", week(1, 0), week(1, 4)).status()).isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-03")
    void twoRequestsOfOneMemberMadeAtTheSameTimeCannotBothPassTheBalanceCheck() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 5));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> attempts = new ArrayList<>();
            for (int week = 1; week <= 4; week++) {
                int w = week;
                attempts.add(() -> create(user, "ODIHNA", week(w, 0), week(w, 4)).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                statuses.add(future.get());
            }

            assertThat(statuses).filteredOn(s -> s == 201).as(statuses.toString()).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
            assertThat(balance(user, null)).containsExactly(2026, 5, 0, 5, 0);
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================================================
    // states (REQ-LEAVE-04)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-04")
    void anApproverApprovesAWaitingRequestWithAnOptionalComment() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String withComment = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();
        String without = create(user, "ODIHNA", week(2, 0), week(2, 0)).body().get("id").asText();

        Resp approved = call(HttpMethod.POST, "/leave/requests/" + withComment + "/approve", manager, tenant, Map.of("comment", "Enjoy"));
        Resp bare = call(HttpMethod.POST, "/leave/requests/" + without + "/approve", admin, tenant, null);

        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.body().get("decidedBy").asText()).isEqualTo(manager.toString());
        assertThat(approved.body().get("decisionComment").asText()).isEqualTo("Enjoy");
        assertThat(approved.body().get("decidedAt").isNull()).isFalse();
        assertThat(approved.body().get("autoApproved").asBoolean()).isFalse();
        assertThat(bare.status()).isEqualTo(200);
        assertThat(bare.body().get("decidedBy").asText()).isEqualTo(admin.toString());
        assertThat(bare.body().get("decisionComment").isNull()).isTrue();
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void anApproverRejectsAWaitingRequestWithAnOptionalComment() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();

        Resp rejected = call(HttpMethod.POST, "/leave/requests/" + id + "/reject", manager, tenant, Map.of("comment", "Release week"));

        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("decisionComment").asText()).isEqualTo("Release week");
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/reject", manager, tenant, null).status()).as("a second decision").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/approve", manager, tenant, null).status()).as("cannot be turned around").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", user, tenant, null).status()).as("nothing left to cancel").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + create(user, "ODIHNA", week(2, 0), week(2, 0)).body().get("id").asText() + "/approve",
                manager, tenant, Map.of("comment", "c".repeat(501))).status()).as("a comment is at most 500 characters").isEqualTo(400);
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void theRequesterCancelsAWaitingRequest() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();

        Resp cancelled = call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", user, tenant, null);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().get("cancelledBy").asText()).isEqualTo(user.toString());
        assertThat(cancelled.body().get("cancelledAt").isNull()).isFalse();
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", user, tenant, null).status()).as("twice").isEqualTo(409);
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void anApprovedLeaveCanBeCancelledByItsRequesterOrByAnApproverOnlyBeforeItStarts() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String byRequester = approved(user, manager, week(1, 0), week(1, 4));
        String byManager = approved(user, manager, week(2, 0), week(2, 4));
        String byAdmin = approved(user, manager, week(3, 0), week(3, 4));

        Resp requester = call(HttpMethod.POST, "/leave/requests/" + byRequester + "/cancel", user, tenant, null);
        Resp viaManager = call(HttpMethod.POST, "/leave/requests/" + byManager + "/cancel", manager, tenant, null);
        Resp viaAdmin = call(HttpMethod.POST, "/leave/requests/" + byAdmin + "/cancel", admin, tenant, null);

        for (Resp resp : List.of(requester, viaManager, viaAdmin)) {
            assertThat(resp.status()).isEqualTo(200);
            assertThat(resp.body().get("status").asText()).isEqualTo("CANCELLED");
        }
        assertThat(requester.body().get("cancelledBy").asText()).isEqualTo(user.toString());
        assertThat(viaManager.body().get("cancelledBy").asText()).isEqualTo(manager.toString());
        assertThat(viaAdmin.body().get("cancelledBy").asText()).isEqualTo(admin.toString());
        assertThat(viaManager.body().get("decidedBy").asText()).as("who approved it is kept").isEqualTo(manager.toString());
        assertThat(balance(user, null)).containsExactly(2026, 21, 0, 0, 21);
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void aLeaveThatHasStartedOrIsOverCanNoLongerBeCancelled() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String id = approved(user, manager, week(1, 0), week(1, 4)); // Monday 2026-03-09

        clock.setInstant(Instant.parse("2026-03-08T21:30:00Z")); // Sunday 23:30 in Bucharest: starts tomorrow
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", manager, tenant, null).status()).as("the evening before").isEqualTo(200);

        String second = approved(user, manager, week(2, 0), week(2, 4)); // Monday 2026-03-16
        clock.setInstant(Instant.parse("2026-03-15T22:30:00Z")); // Monday 00:30 in Bucharest: today is the first day
        assertThat(call(HttpMethod.POST, "/leave/requests/" + second + "/cancel", user, tenant, null).status()).as("on the first day").isEqualTo(409);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + second + "/cancel", manager, tenant, null).status()).as("an approver too").isEqualTo(409);
        clock.setInstant(Instant.parse("2026-03-30T10:00:00Z"));
        assertThat(call(HttpMethod.POST, "/leave/requests/" + second + "/cancel", user, tenant, null).status()).as("long over").isEqualTo(409);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + second, user, tenant, null).body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(balance(user, null)).as("its days stay used").containsExactly(2026, 21, 5, 0, 16);
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void aWaitingRequestIsOnlyEverCancelledByItsRequesterNotByAnApprover() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();

        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", manager, tenant, null).status()).as("they reject it instead").isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", admin, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + id, user, tenant, null).body().get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void aWaitingRequestWhoseFirstDayHasPassedCanStillBeCancelledByItsRequester() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();

        clock.setInstant(Instant.parse("2026-03-11T10:00:00Z"));

        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", user, tenant, null).status()).isEqualTo(200);
        assertThat(balance(user, null)).containsExactly(2026, 21, 0, 0, 21);
    }

    @Test
    @Tag("REQ-LEAVE-04")
    @Tag("REQ-LEAVE-05")
    void theMatrixOfRoleActionAndStatusForADecisionOnSomeoneElsesRequest() throws Exception {
        UUID requester = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID otherUser = addMember(tenant, "USER", false);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);
        UUID readOnlyAdmin = addMember(tenant, "ADMIN", true);
        UUID outsider = UUID.randomUUID();
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 366)); // enough days for every request below
        // role -> expected status of approve, reject on a WAITING request; then the same on a request already APPROVED
        Map<String, UUID> callers = new java.util.LinkedHashMap<>();
        callers.put("ADMIN", admin);
        callers.put("MANAGER", manager);
        callers.put("USER", otherUser);
        callers.put("read-only MANAGER", readOnlyManager);
        callers.put("read-only ADMIN", readOnlyAdmin);
        callers.put("requester", requester);
        callers.put("non-member", outsider);
        Map<String, Integer> onWaiting = Map.of("ADMIN", 200, "MANAGER", 200, "USER", 403, "read-only MANAGER", 403,
                "read-only ADMIN", 403, "requester", 403, "non-member", 403);
        Map<String, Integer> onApproved = Map.of("ADMIN", 409, "MANAGER", 409, "USER", 403, "read-only MANAGER", 403,
                "read-only ADMIN", 403, "requester", 403, "non-member", 403);

        for (String action : List.of("approve", "reject")) {
            for (Map.Entry<String, UUID> caller : callers.entrySet()) {
                String waiting = create(requester, "FARA_PLATA", nextFreeDay(), nextFreeDay()).body().get("id").asText();
                assertThat(call(HttpMethod.POST, "/leave/requests/" + waiting + "/" + action, caller.getValue(), tenant, null).status())
                        .as(action + " by " + caller.getKey() + " on a waiting request").isEqualTo(onWaiting.get(caller.getKey()));

                String approvedOne = approved(requester, manager, nextFreeDay(), nextFreeDay());
                assertThat(call(HttpMethod.POST, "/leave/requests/" + approvedOne + "/" + action, caller.getValue(), tenant, null).status())
                        .as(action + " by " + caller.getKey() + " on an approved request").isEqualTo(onApproved.get(caller.getKey()));
            }
        }
    }

    @Test
    @Tag("REQ-LEAVE-04")
    void theMatrixOfRoleAndStatusForCancelling() throws Exception {
        UUID requester = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID otherUser = addMember(tenant, "USER", false);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);
        UUID outsider = UUID.randomUUID();
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 366));
        Map<String, UUID> callers = new java.util.LinkedHashMap<>();
        callers.put("requester", requester);
        callers.put("ADMIN", admin);
        callers.put("MANAGER", manager);
        callers.put("other USER", otherUser);
        callers.put("read-only MANAGER", readOnlyManager);
        callers.put("non-member", outsider);
        Map<String, Integer> onWaiting = Map.of("requester", 200, "ADMIN", 403, "MANAGER", 403, "other USER", 404,
                "read-only MANAGER", 403, "non-member", 403);
        Map<String, Integer> onApproved = Map.of("requester", 200, "ADMIN", 200, "MANAGER", 200, "other USER", 404,
                "read-only MANAGER", 403, "non-member", 403);

        for (Map.Entry<String, UUID> caller : callers.entrySet()) {
            String waiting = create(requester, "FARA_PLATA", nextFreeDay(), nextFreeDay()).body().get("id").asText();
            assertThat(call(HttpMethod.POST, "/leave/requests/" + waiting + "/cancel", caller.getValue(), tenant, null).status())
                    .as("cancel by " + caller.getKey() + " a waiting request").isEqualTo(onWaiting.get(caller.getKey()));
            String approvedOne = approved(requester, manager, nextFreeDay(), nextFreeDay());
            assertThat(call(HttpMethod.POST, "/leave/requests/" + approvedOne + "/cancel", caller.getValue(), tenant, null).status())
                    .as("cancel by " + caller.getKey() + " an approved request").isEqualTo(onApproved.get(caller.getKey()));
        }
        assertThat(call(HttpMethod.POST, "/leave/requests/" + UUID.randomUUID() + "/cancel", requester, tenant, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/leave/requests/not-an-id/cancel", requester, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + UUID.randomUUID() + "/approve", manager, tenant, null).status()).isEqualTo(404);
    }

    // =====================================================================================================
    // who approves (REQ-LEAVE-05)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-05")
    void nobodyApprovesOrRejectsTheirOwnRequestEvenWhenTheyAreAnApprover() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        String adminsRequest = create(admin, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();
        String managersRequest = create(manager, "ODIHNA", week(2, 0), week(2, 0)).body().get("id").asText();

        assertThat(call(HttpMethod.POST, "/leave/requests/" + adminsRequest + "/approve", admin, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + adminsRequest + "/reject", admin, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + managersRequest + "/approve", manager, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + adminsRequest + "/approve", manager, tenant, null).status()).as("the other approver can").isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + managersRequest + "/approve", admin, tenant, null).status()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-LEAVE-05")
    @Tag("REQ-MON-01")
    void aRequesterWhoIsTheOnlyApproverOfTheTenantIsApprovedAtOnceAndItIsJournaled() throws Exception {
        UUID user = addMember(tenant, "USER", false); // the admin is the only MANAGER/ADMIN

        Resp own = create(admin, "ODIHNA", week(1, 0), week(1, 4));

        assertThat(own.status()).isEqualTo(201);
        assertThat(own.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(own.body().get("autoApproved").asBoolean()).isTrue();
        assertThat(own.body().get("decidedBy").asText()).isEqualTo(admin.toString());
        assertThat(own.body().get("decidedAt").isNull()).isFalse();
        assertThat(balance(admin, null)).containsExactly(2026, 21, 5, 0, 16);

        List<JsonNode> auto = events(tenant, "LEAVE_AUTO_APPROVED");
        assertThat(auto).hasSize(1);
        assertThat(auto.get(0).get("requestId").asText()).isEqualTo(own.body().get("id").asText());
        assertThat(auto.get(0).get("requesterUserId").asText()).isEqualTo(admin.toString());
        assertThat(auto.get(0).get("previousStatus").isNull()).as("it never waited").isTrue();
        assertThatMatchesTheJournalCatalog("LEAVE_AUTO_APPROVED", auto.get(0), admin);
        assertThat(events(tenant, "LEAVE_REQUESTED")).as("nobody to ask").isEmpty();

        Resp userRequest = create(user, "ODIHNA", week(2, 0), week(2, 0));
        assertThat(userRequest.body().get("status").asText()).as("an ordinary member is never auto-approved").isEqualTo("PENDING");
        assertThat(events(tenant, "LEAVE_AUTO_APPROVED")).hasSize(1);
    }

    @Test
    @Tag("REQ-LEAVE-05")
    void anApproverWhoseOnlyColleaguesAreReadOnlyIsStillTheOnlyApprover() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);
        update(tenant, "update org.memberships set read_only = true where user_id = ?", admin);

        Resp own = create(manager, "ODIHNA", week(1, 0), week(1, 0));

        assertThat(own.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(own.body().get("autoApproved").asBoolean()).isTrue();
    }

    @Test
    @Tag("REQ-LEAVE-05")
    void anApproverIsNotAutoApprovedWhenAnotherWritableApproverExists() throws Exception {
        UUID manager = addMember(tenant, "MANAGER", false);

        Resp own = create(manager, "ODIHNA", week(1, 0), week(1, 0));

        assertThat(own.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(own.body().get("autoApproved").asBoolean()).isFalse();
        assertThat(events(tenant, "LEAVE_AUTO_APPROVED")).isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-05")
    void aMemberWhenNoOneCanApproveSimplyWaits() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        update(tenant, "update org.memberships set read_only = true where user_id = ?", admin);

        Resp own = create(user, "ODIHNA", week(1, 0), week(1, 0));

        assertThat(own.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(events(tenant, "LEAVE_REQUESTED")).as("there is nobody to tell").isEmpty();
    }

    // =====================================================================================================
    // who sees what (REQ-LEAVE-06)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-06")
    void aMemberSeesTheirOwnRequestsWithTheReasonAndNeverSomeoneElses() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID bob = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String mine = call(HttpMethod.POST, "/leave/requests", alice, tenant, Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 0).toString(), "reason", "My own secret")).body().get("id").asText();
        String bobs = call(HttpMethod.POST, "/leave/requests", bob, tenant, Map.of("type", "ODIHNA", "startDate", week(2, 0).toString(),
                "endDate", week(2, 0).toString(), "reason", "Bob's secret")).body().get("id").asText();

        Resp list = call(HttpMethod.GET, "/leave/requests", alice, tenant, null);
        assertThat(list.body().get("items")).hasSize(1);
        assertThat(list.body().get("items").get(0).get("id").asText()).isEqualTo(mine);
        assertThat(list.body().get("items").get(0).get("reason").asText()).isEqualTo("My own secret");
        assertThat(list.toString()).doesNotContain("Bob's secret");
        assertThat(call(HttpMethod.GET, "/leave/requests/" + mine, alice, tenant, null).status()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + bobs, alice, tenant, null).status()).as("someone else's does not exist for them").isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=ALL", alice, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/requests?userId=" + bob, alice, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=ALL&userId=" + bob, alice, tenant, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=SOME", alice, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + bobs, UUID.randomUUID(), tenant, null).status()).as("a non-member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-LEAVE-06")
    void managersAndAdminsSeeEveryRequestWithItsTypeAndReasonAndCanFilterThem() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID bob = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String a = call(HttpMethod.POST, "/leave/requests", alice, tenant, Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 0).toString(), "reason", "Alice's reason")).body().get("id").asText();
        String b = call(HttpMethod.POST, "/leave/requests", bob, tenant, Map.of("type", "FARA_PLATA", "startDate", week(2, 0).toString(),
                "endDate", week(2, 0).toString(), "reason", "Bob's reason")).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + b + "/approve", manager, tenant, null);

        for (UUID approver : List.of(manager, admin)) {
            Resp all = call(HttpMethod.GET, "/leave/requests?scope=ALL", approver, tenant, null);
            assertThat(all.body().get("totalItems").asInt()).isEqualTo(2);
            assertThat(all.body().get("items").get(0).get("id").asText()).as("newest start first").isEqualTo(b);
            assertThat(all.body().get("items").get(0).get("type").asText()).isEqualTo("FARA_PLATA");
            assertThat(all.body().get("items").get(1).get("reason").asText()).isEqualTo("Alice's reason");
            assertThat(call(HttpMethod.GET, "/leave/requests/" + a, approver, tenant, null).body().get("reason").asText()).isEqualTo("Alice's reason");
        }
        assertThat(ids(call(HttpMethod.GET, "/leave/requests?scope=ALL&status=PENDING", manager, tenant, null))).containsExactly(a);
        assertThat(ids(call(HttpMethod.GET, "/leave/requests?scope=ALL&status=APPROVED", manager, tenant, null))).containsExactly(b);
        assertThat(ids(call(HttpMethod.GET, "/leave/requests?scope=ALL&userId=" + alice, manager, tenant, null))).containsExactly(a);
        assertThat(ids(call(HttpMethod.GET, "/leave/requests?scope=ALL&status=REJECTED", manager, tenant, null))).isEmpty();
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=ALL&status=MAYBE", manager, tenant, null).status()).isEqualTo(400);
        assertThat(ids(call(HttpMethod.GET, "/leave/requests", manager, tenant, null))).as("their own list is their own").isEmpty();
    }

    @Test
    @Tag("REQ-LEAVE-06")
    void theListIsPagedNewestFirstWithinBounds() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        List<String> newestFirst = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            newestFirst.add(0, create(user, "FARA_PLATA", week(i, 0), week(i, 0)).body().get("id").asText());
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Resp result = call(HttpMethod.GET, "/leave/requests?size=3&page=" + page, user, tenant, null);
            assertThat(result.body().get("totalItems").asInt()).isEqualTo(7);
            seen.addAll(ids(result));
        }

        assertThat(seen).containsExactlyElementsOf(newestFirst);
        assertThat(ids(call(HttpMethod.GET, "/leave/requests?size=3&page=3", user, tenant, null))).isEmpty();
        for (String bad : List.of("page=-1", "size=0", "size=101", "size=abc")) {
            assertThat(call(HttpMethod.GET, "/leave/requests?" + bad, user, tenant, null).status()).as(bad).isEqualTo(400);
        }
        assertThat(call(HttpMethod.GET, "/leave/requests?size=100", user, tenant, null).status()).isEqualTo(200);
    }

    @Test
    @Tag("REQ-LEAVE-06")
    void theTeamCalendarShowsEveryMemberOnlyApprovedLeaveAsWhoAndWhenWithNothingElse() throws Exception {
        UUID alice = addMember(tenant, "USER", false);
        UUID bob = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID readOnlyUser = addMember(tenant, "USER", true);
        String approvedOne = call(HttpMethod.POST, "/leave/requests", alice, tenant, Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 4).toString(), "reason", "Hidden reason")).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + approvedOne + "/approve", manager, tenant, Map.of("comment", "Hidden comment"));
        create(bob, "ODIHNA", week(2, 0), week(2, 0)); // waiting
        String rejected = create(bob, "ODIHNA", week(3, 0), week(3, 0)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + rejected + "/reject", manager, tenant, null);
        String cancelled = approved(bob, manager, week(4, 0), week(4, 0));
        call(HttpMethod.POST, "/leave/requests/" + cancelled + "/cancel", bob, tenant, null);

        for (UUID viewer : List.of(alice, bob, manager, admin, readOnlyUser)) {
            Resp calendar = call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-04-30", viewer, tenant, null);
            assertThat(calendar.status()).isEqualTo(200);
            assertThat(calendar.body()).as("only the approved one").hasSize(1);
            JsonNode entry = calendar.body().get(0);
            assertThat(entry.get("userId").asText()).isEqualTo(alice.toString());
            assertThat(entry.get("startDate").asText()).isEqualTo(week(1, 0).toString());
            assertThat(entry.get("endDate").asText()).isEqualTo(week(1, 4).toString());
            List<String> fields = new ArrayList<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).as("no type, reason, status, days or comment").containsExactlyInAnyOrder("userId", "startDate", "endDate");
            assertThat(calendar.body().toString()).doesNotContain("Hidden").doesNotContain("ODIHNA");
        }
    }

    @Test
    @Tag("REQ-LEAVE-06")
    void theCalendarCoversTheRequestedRangeAndRefusesNonsense() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        approved(user, manager, week(1, 0), week(1, 4)); // 2026-03-09 .. 2026-03-13

        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-13&to=2026-03-20", user, tenant, null).body()).as("shares the last day").hasSize(1);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-09", user, tenant, null).body()).as("shares the first day").hasSize(1);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-14&to=2026-03-31", user, tenant, null).body()).as("after it").isEmpty();
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-02-01&to=2026-03-08", user, tenant, null).body()).as("before it").isEmpty();
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-20&to=2026-03-01", user, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-01-01&to=2027-01-03", user, tenant, null).status()).as("more than 366 days").isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-01-01&to=2027-01-02", user, tenant, null).status()).as("366 days after the start is the limit").isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01", user, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=x&to=y", user, tenant, null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-31", UUID.randomUUID(), tenant, null).status()).as("a non-member").isEqualTo(403);
    }

    @Test
    @Tag("REQ-LEAVE-06")
    void everyAnswerAboutLeaveIsMarkedNotToBeCached() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        String id = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();

        for (String path : List.of("/leave/requests", "/leave/requests/" + id, "/leave/balance", "/leave/calendar?from=2026-03-01&to=2026-03-31")) {
            MvcResult result = mockMvc.perform(withIdentity(MockMvcRequestBuilders.get("/organizations/{t}" + path, tenant), user, tenant)).andReturn();
            assertThat(result.getResponse().getHeader("Cache-Control")).as(path).contains("no-store");
        }
    }

    // =====================================================================================================
    // what the module tells the other services (REQ-LEAVE-07, REQ-MON-01, REQ-NOTIF-03, REQ-EVT-02)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-EVT-02")
    void aNewRequestIsAnnouncedToTheOtherApproversWhoCanActAndNoOneElse() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        UUID readOnlyManager = addMember(tenant, "MANAGER", true);
        UUID otherUser = addMember(tenant, "USER", false);
        String reason = "Private reason " + UUID.randomUUID();

        Resp created = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 4).toString(), "reason", reason));

        List<JsonNode> announced = events(tenant, "LEAVE_REQUESTED");
        assertThat(announced).hasSize(1);
        JsonNode event = announced.get(0);
        assertThat(event.get("requestId").asText()).isEqualTo(created.body().get("id").asText());
        assertThat(event.get("requesterUserId").asText()).isEqualTo(user.toString());
        assertThat(texts(event.get("approverUserIds"))).containsExactlyInAnyOrder(admin.toString(), manager.toString());
        assertThat(texts(event.get("approverUserIds"))).doesNotContain(readOnlyManager.toString(), otherUser.toString(), user.toString());
        assertThat(event.get("startDate").asText()).isEqualTo(week(1, 0).toString());
        assertThat(event.get("endDate").asText()).isEqualTo(week(1, 4).toString());
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("tenantId", "requestId", "requesterUserId", "approverUserIds", "startDate", "endDate");
        assertThat(allOutbox(tenant)).as("the reason never leaves the service").doesNotContain(reason);
        assertThat(outboxChannels(tenant, "LEAVE_REQUESTED")).containsExactly("KAFKA:org.events");
    }

    @Test
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-MON-01")
    void anApprovalAndARejectionAreAnnouncedWithWhoDecidedAndAreAuditedWithoutTheComment() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String first = create(user, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();
        String second = create(user, "FARA_PLATA", week(2, 0), week(2, 1)).body().get("id").asText();
        String comment = "Private comment " + UUID.randomUUID();

        call(HttpMethod.POST, "/leave/requests/" + first + "/approve", manager, tenant, Map.of("comment", comment));
        call(HttpMethod.POST, "/leave/requests/" + second + "/reject", admin, tenant, Map.of("comment", comment));

        JsonNode approval = events(tenant, "LEAVE_APPROVED").get(0);
        assertThat(approval.get("requestId").asText()).isEqualTo(first);
        assertThat(approval.get("requesterUserId").asText()).isEqualTo(user.toString());
        assertThat(approval.get("leaveType").asText()).isEqualTo("ODIHNA");
        assertThat(approval.get("days").asInt()).isEqualTo(5);
        assertThat(approval.get("previousStatus").asText()).isEqualTo("PENDING");
        assertThatMatchesTheJournalCatalog("LEAVE_APPROVED", approval, manager);
        JsonNode rejection = events(tenant, "LEAVE_REJECTED").get(0);
        assertThat(rejection.get("requestId").asText()).isEqualTo(second);
        assertThat(rejection.get("leaveType").asText()).isEqualTo("FARA_PLATA");
        assertThat(rejection.get("days").asInt()).isEqualTo(2);
        assertThatMatchesTheJournalCatalog("LEAVE_REJECTED", rejection, admin);
        assertThat(allOutbox(tenant)).doesNotContain(comment);
        assertThat(events(tenant, "LEAVE_APPROVED")).hasSize(1);
        assertThat(events(tenant, "LEAVE_REJECTED")).hasSize(1);
    }

    @Test
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-MON-01")
    void cancellingAnApprovedLeaveIsAuditedWithWhoDidItButCancellingAWaitingRequestIsNot() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String waiting = create(user, "ODIHNA", week(1, 0), week(1, 0)).body().get("id").asText();
        String byApprover = approved(user, manager, week(2, 0), week(2, 4));
        String bySelf = approved(user, manager, week(3, 0), week(3, 1));

        call(HttpMethod.POST, "/leave/requests/" + waiting + "/cancel", user, tenant, null);
        assertThat(events(tenant, "LEAVE_CANCELLED")).as("a waiting request leaves no audit trail").isEmpty();
        call(HttpMethod.POST, "/leave/requests/" + byApprover + "/cancel", manager, tenant, null);
        call(HttpMethod.POST, "/leave/requests/" + bySelf + "/cancel", user, tenant, null);

        List<JsonNode> cancelled = events(tenant, "LEAVE_CANCELLED");
        assertThat(cancelled).hasSize(2);
        assertThat(cancelled.get(0).get("requestId").asText()).isEqualTo(byApprover);
        assertThat(cancelled.get(0).get("actorUserId").asText()).as("the notification goes to the requester because the actor is someone else").isEqualTo(manager.toString());
        assertThat(cancelled.get(0).get("requesterUserId").asText()).isEqualTo(user.toString());
        assertThat(cancelled.get(0).get("previousStatus").asText()).isEqualTo("APPROVED");
        assertThatMatchesTheJournalCatalog("LEAVE_CANCELLED", cancelled.get(0), manager);
        assertThat(cancelled.get(1).get("actorUserId").asText()).isEqualTo(user.toString());
        assertThatMatchesTheJournalCatalog("LEAVE_CANCELLED", cancelled.get(1), user);
    }

    @Test
    @Tag("REQ-LEAVE-07")
    @Tag("REQ-MON-01")
    void everyChangeOfTheLeavePolicyIsAuditedWithWhatChangedAndNothingWhenNothingChanged() throws Exception {
        UUID user = addMember(tenant, "USER", false);

        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 25));
        call(HttpMethod.PUT, "/leave/policy", admin, tenant, Map.of("allowance", 25)); // same value
        call(HttpMethod.PUT, "/leave/balances/" + user, admin, tenant, Map.of("allowance", 30));
        call(HttpMethod.PUT, "/leave/balances/" + user, admin, tenant, Map.of("allowance", 30)); // same value
        call(HttpMethod.DELETE, "/leave/balances/" + user, admin, tenant, null);
        call(HttpMethod.DELETE, "/leave/balances/" + user, admin, tenant, null); // nothing to remove
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "Christmas"));
        call(HttpMethod.DELETE, "/leave/holidays/2026-12-25", admin, tenant, null);

        List<JsonNode> changes = events(tenant, "LEAVE_POLICY_CHANGED");
        assertThat(changes).extracting(c -> c.get("change").asText()).containsExactly("DEFAULT_ALLOWANCE", "BALANCE_OVERRIDE",
                "BALANCE_OVERRIDE_REMOVED", "HOLIDAY_ADDED", "HOLIDAY_REMOVED");
        assertThat(changes.get(0).get("allowance").asInt()).isEqualTo(25);
        assertThat(changes.get(1).get("targetUserId").asText()).isEqualTo(user.toString());
        assertThat(changes.get(1).get("allowance").asInt()).isEqualTo(30);
        assertThat(changes.get(2).get("targetUserId").asText()).isEqualTo(user.toString());
        assertThat(changes.get(3).get("holidayDate").asText()).isEqualTo("2026-12-25");
        assertThat(changes.get(4).get("holidayDate").asText()).isEqualTo("2026-12-25");
        for (JsonNode change : changes) {
            assertThatMatchesTheJournalCatalog("LEAVE_POLICY_CHANGED", change, admin);
        }
        assertThat(allOutbox(tenant)).as("a holiday's name is free text and stays here").doesNotContain("Christmas");
    }

    @Test
    @Tag("REQ-EVT-02")
    void noEventOfTheModuleCarriesANameAnEmailOrFreeText() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        String secret = "free-text-" + UUID.randomUUID();
        String id = call(HttpMethod.POST, "/leave/requests", user, tenant, Map.of("type", "ODIHNA", "startDate", week(1, 0).toString(),
                "endDate", week(1, 0).toString(), "reason", secret)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + id + "/approve", manager, tenant, Map.of("comment", secret));
        call(HttpMethod.POST, "/leave/requests/" + id + "/cancel", manager, tenant, null);
        call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", secret));

        String everything = allOutbox(tenant);

        assertThat(everything).doesNotContain(secret).doesNotContain("@").doesNotContainIgnoringCase("\"reason\"")
                .doesNotContainIgnoringCase("\"comment\"").doesNotContainIgnoringCase("\"name\"").doesNotContainIgnoringCase("\"email\"");
        assertThat(outboxChannels(tenant, "LEAVE_APPROVED")).containsExactly("KAFKA:org.events");
    }

    // =====================================================================================================
    // offboarding (REQ-LEAVE-08)
    // =====================================================================================================

    @Test
    @Tag("REQ-LEAVE-08")
    void offboardingAMemberDeletesTheirLeaveRequestsAndAllowanceOverrideInThatTenantOnly() throws Exception {
        UUID leaving = addMember(tenant, "USER", false);
        UUID staying = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        approved(leaving, manager, week(1, 0), week(1, 4));
        create(leaving, "ODIHNA", week(2, 0), week(2, 0));
        create(leaving, "FARA_PLATA", week(3, 0), week(3, 0));
        approved(staying, manager, week(1, 0), week(1, 4));
        call(HttpMethod.PUT, "/leave/balances/" + leaving, admin, tenant, Map.of("allowance", 30));
        call(HttpMethod.PUT, "/leave/balances/" + staying, admin, tenant, Map.of("allowance", 28));
        UUID otherAdmin = UUID.randomUUID();
        UUID otherTenant = createOrganization(otherAdmin);
        call(HttpMethod.PUT, "/modules/LEAVE", otherAdmin, otherTenant, Map.of("enabled", true));
        insertMembership(otherTenant, leaving, "USER", false);
        insertMembership(otherTenant, UUID.randomUUID(), "MANAGER", false);
        create(otherTenant, leaving, "ODIHNA", week(1, 0), week(1, 0));

        MvcResult offboarded = mockMvc.perform(withIdentity(MockMvcRequestBuilders.delete("/organizations/{t}/members/{u}", tenant, leaving), admin, tenant)).andReturn();

        assertThat(offboarded.getResponse().getStatus()).isEqualTo(204);
        assertThat(rows(tenant, "select count(*) from org.leave_requests where user_id = ?", leaving)).isZero();
        assertThat(rows(tenant, "select count(*) from org.leave_balance_overrides where user_id = ?", leaving)).isZero();
        assertThat(rows(tenant, "select count(*) from org.leave_requests where user_id = ?", staying)).as("others keep theirs").isEqualTo(1);
        assertThat(rows(tenant, "select count(*) from org.leave_balance_overrides where user_id = ?", staying)).isEqualTo(1);
        assertThat(rows(otherTenant, "select count(*) from org.leave_requests where user_id = ?", leaving)).as("the same person in another tenant").isEqualTo(1);
        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-03-01&to=2026-03-31", staying, tenant, null).body()).hasSize(1);
    }

    @Test
    @Tag("REQ-LEAVE-08")
    void aMemberWhoJoinsAgainAfterBeingOffboardedStartsWithNoLeaveHistory() throws Exception {
        UUID leaving = addMember(tenant, "USER", false);
        UUID manager = addMember(tenant, "MANAGER", false);
        approved(leaving, manager, week(1, 0), week(1, 4));
        call(HttpMethod.PUT, "/leave/balances/" + leaving, admin, tenant, Map.of("allowance", 30));
        mockMvc.perform(withIdentity(MockMvcRequestBuilders.delete("/organizations/{t}/members/{u}", tenant, leaving), admin, tenant));

        insertMembership(tenant, leaving, "USER", false);

        assertThat(call(HttpMethod.GET, "/leave/requests", leaving, tenant, null).body().get("items")).isEmpty();
        assertThat(balance(leaving, null)).containsExactly(2026, 21, 0, 0, 21);
        assertThat(create(leaving, "ODIHNA", week(1, 0), week(1, 4)).status()).as("the days are free again").isEqualTo(201);
    }

    @Test
    @Tag("REQ-LEAVE-08")
    void theDatabaseRefusesToRemoveAMembershipThatStillHasLeaveRequests() throws Exception {
        UUID user = addMember(tenant, "USER", false);
        addMember(tenant, "MANAGER", false);
        create(user, "ODIHNA", week(1, 0), week(1, 0));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> update(tenant, "delete from org.memberships where user_id = ?", user)))
                .hasMessageContaining("violates foreign key constraint");
    }

    // =====================================================================================================
    // isolation (REQ-DATA-03)
    // =====================================================================================================

    @Test
    @Tag("REQ-MOD-01")
    @Tag("REQ-DATA-03")
    void oneTenantsLeaveDataIsInvisibleAndUntouchableFromAnother() throws Exception {
        UUID userA = addMember(tenant, "USER", false);
        UUID managerA = addMember(tenant, "MANAGER", false);
        String requestA = create(userA, "ODIHNA", week(1, 0), week(1, 4)).body().get("id").asText();
        call(HttpMethod.POST, "/leave/requests/" + requestA + "/approve", managerA, tenant, null);
        UUID adminB = UUID.randomUUID();
        UUID tenantB = createOrganization(adminB);
        call(HttpMethod.PUT, "/modules/LEAVE", adminB, tenantB, Map.of("enabled", true));
        UUID userB = addMember(tenantB, "USER", false);
        UUID managerB = addMember(tenantB, "MANAGER", false);
        String requestB = create(tenantB, userB, "ODIHNA", week(2, 0), week(2, 0)).body().get("id").asText();

        assertThat(call(HttpMethod.GET, "/leave/calendar?from=2026-01-01&to=2026-12-31", userB, tenantB, null).body()).as("B's calendar has none of A's").isEmpty();
        assertThat(call(HttpMethod.GET, "/leave/requests?scope=ALL", managerB, tenantB, null).body().get("items")).hasSize(1);
        assertThat(callWithToken(HttpMethod.GET, tenant, "/leave/requests/" + requestA, managerB, tenantB, null).status()).as("B's token on A's path").isEqualTo(403);
        assertThat(callWithToken(HttpMethod.POST, tenant, "/leave/requests/" + requestA + "/cancel", managerB, tenantB, null).status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + requestA, managerB, tenantB, null).status()).as("A's id on B's path").isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/leave/requests/" + requestA + "/reject", managerB, tenantB, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + requestB, managerA, tenant, null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/leave/requests/" + requestA, managerA, tenant, null).body().get("status").asText()).isEqualTo("APPROVED");

        RlsAssertions rls = new RlsAssertions(dataSource);
        for (String table : List.of("org.leave_requests", "org.public_holidays", "org.leave_balance_overrides")) {
            if (table.equals("org.public_holidays")) {
                call(HttpMethod.POST, "/leave/holidays", admin, tenant, Map.of("date", "2026-12-25", "name", "Christmas"));
                call(HttpMethod.POST, "/leave/holidays", adminB, tenantB, Map.of("date", "2026-12-25", "name", "Christmas"));
            }
            if (table.equals("org.leave_balance_overrides")) {
                call(HttpMethod.PUT, "/leave/balances/" + userA, admin, tenant, Map.of("allowance", 20));
                call(HttpMethod.PUT, "/leave/balances/" + userB, adminB, tenantB, Map.of("allowance", 20));
            }
            rls.assertSeesOnlyOwnRows(table, tenant);
            rls.assertSeesOnlyOwnRows(table, tenantB);
            rls.assertNothingVisibleWithoutTenant(table);
        }
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    /** Monday of the given week after the clock's Monday (week 0 is the current week), plus dayOfWeek days. */
    private static LocalDate week(int weeksAhead, int dayOfWeek) {
        return TODAY.plusWeeks(weeksAhead).plusDays(dayOfWeek);
    }

    private int freeDayCounter = 0;

    /** A working day nobody has used yet in this test: Mondays to Fridays of consecutive weeks, never in the past. */
    private LocalDate nextFreeDay() {
        int index = freeDayCounter++;
        return TODAY.plusWeeks(2 + index / 5).plusDays(index % 5);
    }

    private Resp create(UUID caller, String type, LocalDate start, LocalDate end) throws Exception {
        return create(tenant, caller, type, start, end);
    }

    private Resp create(UUID inTenant, UUID caller, String type, LocalDate start, LocalDate end) throws Exception {
        return call(HttpMethod.POST, "/leave/requests", caller, inTenant,
                Map.of("type", type, "startDate", start.toString(), "endDate", end.toString()));
    }

    /** A request made by {@code requester} and approved by {@code approver}; returns its id. */
    private String approved(UUID requester, UUID approver, LocalDate start, LocalDate end) throws Exception {
        String id = create(requester, "ODIHNA", start, end).body().get("id").asText();
        assertThat(call(HttpMethod.POST, "/leave/requests/" + id + "/approve", approver, tenant, null).status()).isEqualTo(200);
        return id;
    }

    /** year, allocated, approved, pending, available */
    private List<Integer> balance(UUID member, Integer year) throws Exception {
        Resp result = call(HttpMethod.GET, "/leave/balance" + (year == null ? "" : "?year=" + year), member, tenant, null);
        assertThat(result.status()).isEqualTo(200);
        JsonNode body = result.body();
        return List.of(body.get("year").asInt(), body.get("allocated").asInt(), body.get("approved").asInt(),
                body.get("pending").asInt(), body.get("available").asInt());
    }

    private List<String> ids(Resp page) {
        List<String> ids = new ArrayList<>();
        page.body().get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
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

    private static MockHttpServletRequestBuilder withIdentity(MockHttpServletRequestBuilder request, UUID caller, UUID inTenant) {
        return request.header("X-User-Id", caller.toString()).header("X-Tenant-Id", inTenant.toString());
    }

    private UUID createOrganization(UUID creator) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/organizations").contentType("application/json")
                .header("X-User-Id", creator.toString()).content("{\"name\":\"Leave Co\"}")).andReturn();
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

    private List<String> outboxChannels(UUID inTenant, String type) throws Exception {
        return inTenantSession(inTenant, jdbc -> jdbc.queryForList(
                "select destination || ':' || channel from org.outbox where tenant_id = ? and event_type = ?", String.class, inTenant, type));
    }

    private List<String> outboxPayloads(UUID inTenant, String sql, Object... args) throws Exception {
        return inTenantSession(inTenant, jdbc -> jdbc.queryForList(sql, String.class, args));
    }
    /** The payload names the actor and every metadata field the journal's catalog reads for this event type. */
    private void assertThatMatchesTheJournalCatalog(String eventType, JsonNode payload, UUID expectedActor) {
        var entry = ActivityEventCatalog.find(eventType).orElseThrow(() -> new AssertionError(eventType + " is not in the journal catalog"));
        assertThat(payload.get(entry.actorField()).asText()).as(eventType + " actor").isEqualTo(expectedActor.toString());
        List<String> present = fieldNames(payload);
        for (String field : entry.metadataFields()) {
            assertThat(present).as(eventType + " carries " + field + " for the journal").contains(field);
        }
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        Clock clock() {
            return MutableClock.at(NOW);
        }
    }
}
