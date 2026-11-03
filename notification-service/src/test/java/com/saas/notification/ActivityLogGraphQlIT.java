package com.saas.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogPartitionMaintenance;
import com.saas.testsupport.containers.PostgresTestContainer;
import graphql.introspection.Introspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reading the tenant activity journal through GraphQL (REQ-MON-02, REQ-MON-04, REQ-MON-08, REQ-MGR-04):
 * ADMIN and MANAGER only, the caller's own tenant only, filterable and paginated, read-only. Requests go
 * through MockMvc with the identity headers the gateway would set from the token. See
 * {@link JournalFixtures} for what the shared superuser connection does and does not prove about RLS.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class ActivityLogGraphQlIT implements PostgresTestContainer {

    private static final String GRAPHQL = "/notifications/graphql";
    private static final String ALL_FIELDS = "id eventType actorUserId createdAt metadata";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ActivityLogPartitionMaintenance partitions;

    private UUID tenantId;
    private UUID admin;
    private UUID actorA;
    private UUID actorB;

    @BeforeEach
    void freshTenant() {
        // starting a context under the prod profile switches introspection off for the whole JVM (graphql-java keeps it in a static), and
        // this class's context is cached and reused: whether it was on or off here depended on the order the test classes ran in
        Introspection.enabledJvmWide(true);
        partitions.ensureUpcomingPartitions();
        tenantId = UUID.randomUUID();
        admin = UUID.randomUUID();
        actorA = UUID.randomUUID();
        actorB = UUID.randomUUID();
    }

    // ---- who may read (REQ-MON-02, REQ-MGR-04) ----

    @ParameterizedTest(name = "{0} can read the journal")
    @ValueSource(strings = {"ADMIN", "MANAGER"})
    @Tag("REQ-MON-02")
    @Tag("REQ-MGR-04")
    void adminAndManagerCanReadTheirTenantsJournalNewestFirstWithEveryField(String role) throws Exception {
        UUID first = seed("LOGIN", actorA, "{\"role\":\"ADMIN\"}", 1);
        UUID second = seed("MEMBER_ROLE_CHANGED", actorB, "{\"previousRole\":\"USER\",\"newRole\":\"MANAGER\"}", 2);

        JsonNode result = query(tenantId, role, "{ activityLog { items { " + ALL_FIELDS + " } } }");

        assertThat(result.has("errors")).isFalse();
        JsonNode items = result.at("/data/activityLog/items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(items.get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(items.get(0).get("eventType").asText()).isEqualTo("MEMBER_ROLE_CHANGED");
        assertThat(items.get(0).get("actorUserId").asText()).isEqualTo(actorB.toString());
        assertThat(Instant.parse(items.get(0).get("createdAt").asText())).isEqualTo(JournalFixtures.MONTH_START.plus(Duration.ofHours(2)));
        assertThat(objectMapper.readTree(items.get(0).get("metadata").asText()).get("newRole").asText()).isEqualTo("MANAGER");
    }

    @Test
    @Tag("REQ-MON-02")
    void aUserIsRefusedWithForbiddenAndGetsNoData() throws Exception {
        seed("LOGIN", actorA, "{}", 1);

        JsonNode result = query(tenantId, "USER", "{ activityLog { items { id } } }");

        assertThat(errorClassification(result)).isEqualTo("FORBIDDEN");
        assertThat(result.at("/data").isMissingNode() || result.at("/data").isNull()
                || result.at("/data/activityLog").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain(actorA.toString());
    }

    @ParameterizedTest(name = "role {0} is refused")
    @ValueSource(strings = {"READER", "admin", "TEMP", "ROLE_ADMIN", ""})
    @Tag("REQ-MON-02")
    void anyRoleButAdminAndManagerIsRefused(String role) throws Exception {
        seed("LOGIN", actorA, "{}", 1);

        JsonNode result = query(tenantId, role, "{ activityLog { items { id } } }");

        assertThat(errorClassification(result)).isIn("FORBIDDEN", "UNAUTHORIZED");
    }

    @Test
    @Tag("REQ-MON-02")
    void aRequestWithoutTheGatewaysIdentityIsUnauthorized() throws Exception {
        seed("LOGIN", actorA, "{}", 1);
        String body = body("{ activityLog { items { id } } }");

        for (var headers : List.<Map<String, String>>of(
                Map.of(), Map.of("X-Role", "ADMIN"), Map.of("X-Role", "ADMIN", "X-User-Id", admin.toString()),
                Map.of("X-Role", "ADMIN", "X-User-Id", admin.toString(), "X-Tenant-Id", "not-a-uuid"))) {
            var request = post(GRAPHQL).contentType("application/json").content(body);
            headers.forEach(request::header);
            JsonNode result = execute(request);
            assertThat(errorClassification(result)).as(headers.keySet().toString()).isEqualTo("UNAUTHORIZED");
        }
    }

    // ---- tenant isolation (REQ-DATA-03, REQ-MON-08) ----

    @Test
    @Tag("REQ-DATA-03")
    void aTenantNeverSeesAnotherTenantsEntries() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID mine = seed("LOGIN", actorA, "{}", 1);
        JournalFixtures.insert(jdbc, otherTenant, "LOGO_CHANGED", actorB, "{\"secret\":\"theirs\"}", JournalFixtures.MONTH_START.plusSeconds(5));
        JournalFixtures.insert(jdbc, otherTenant, "THEME_CHANGED", actorB, "{}", JournalFixtures.MONTH_START.plusSeconds(6));

        JsonNode result = query(tenantId, "ADMIN", "{ activityLog { totalItems items { " + ALL_FIELDS + " } } }");

        assertThat(result.at("/data/activityLog/totalItems").asInt()).isEqualTo(1);
        assertThat(result.at("/data/activityLog/items")).hasSize(1);
        assertThat(result.at("/data/activityLog/items/0/id").asText()).isEqualTo(mine.toString());
        assertThat(result.toString()).doesNotContain("theirs", actorB.toString());
    }

    @Test
    @Tag("REQ-MON-08")
    void theTenantCannotBePassedAsAnArgumentOfTheQueryOrTheFilter() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        JournalFixtures.insert(jdbc, otherTenant, "LOGO_CHANGED", actorB, "{}", JournalFixtures.MONTH_START.plusSeconds(5));

        for (String query : List.of(
                "{ activityLog(tenantId: \"" + otherTenant + "\") { items { id } } }",
                "{ activityLog(filter: { tenantId: \"" + otherTenant + "\" }) { items { id } } }",
                "{ activityLog { items { tenantId } } }")) {
            JsonNode result = query(tenantId, "ADMIN", query);
            assertThat(result.has("errors")).as(query).isTrue();
            assertThat(result.at("/data").isMissingNode() || result.at("/data").isNull()).as(query).isTrue();
        }
    }

    @Test
    @Tag("REQ-MON-08")
    void theTenantComesFromTheCallersIdentityNotFromAnythingInTheRequestBody() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID theirs = JournalFixtures.insert(jdbc, otherTenant, "LOGO_CHANGED", actorB, "{}", JournalFixtures.MONTH_START.plusSeconds(5));
        seed("LOGIN", actorA, "{}", 1);

        String queryWithVariables = objectMapper.writeValueAsString(Map.of(
                "query", "query($f: ActivityLogFilter) { activityLog(filter: $f) { items { id } } }",
                "variables", Map.of("f", Map.of("actorUserId", actorB.toString()))));
        var request = post(GRAPHQL).contentType("application/json").content(queryWithVariables)
                .header("X-User-Id", admin.toString()).header("X-Tenant-Id", tenantId.toString()).header("X-Role", "ADMIN");

        JsonNode result = execute(request);

        assertThat(result.at("/data/activityLog/items")).isEmpty(); // actorB acted only in the other tenant
        assertThat(result.toString()).doesNotContain(theirs.toString());
    }

    // ---- read-only (REQ-MON-08) ----

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {
            "mutation { activityLog { items { id } } }",
            "mutation { deleteActivityLog(id: \"x\") }",
            "mutation { clearActivityLog }",
            "subscription { activityLog { items { id } } }"})
    @Tag("REQ-MON-08")
    void thereAreNoMutationsAndNoSubscriptions(String operation) throws Exception {
        seed("LOGIN", actorA, "{}", 1);

        JsonNode result = query(tenantId, "ADMIN", operation);

        assertThat(result.has("errors")).isTrue();
        assertThat(result.at("/data").isMissingNode() || result.at("/data").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notif.activity_log where tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @Tag("REQ-MON-08")
    void theSchemaDeclaresOnlyAQueryTypeWithNoTenantArgument() throws Exception {
        JsonNode result = query(tenantId, "ADMIN", "{ __schema { queryType { name } mutationType { name } subscriptionType { name } } }");

        assertThat(result.at("/data/__schema/queryType/name").asText()).isEqualTo("Query");
        assertThat(result.at("/data/__schema/mutationType").isNull()).isTrue();
        assertThat(result.at("/data/__schema/subscriptionType").isNull()).isTrue();

        JsonNode args = query(tenantId, "ADMIN", "{ __type(name: \"Query\") { fields { name args { name } } } }").at("/data/__type/fields");
        List<String> names = new ArrayList<>();
        args.forEach(field -> field.get("args").forEach(arg -> names.add(field.get("name").asText() + "." + arg.get("name").asText())));
        assertThat(names).containsExactlyInAnyOrder("activityLog.filter", "activityLog.page", "activityLog.size");
        JsonNode filterFields = query(tenantId, "ADMIN", "{ __type(name: \"ActivityLogFilter\") { inputFields { name } } }").at("/data/__type/inputFields");
        List<String> filter = new ArrayList<>();
        filterFields.forEach(f -> filter.add(f.get("name").asText()));
        assertThat(filter).containsExactlyInAnyOrder("from", "to", "eventTypes", "actorUserId");
    }

    @Test
    @Tag("REQ-MON-08")
    void introspectionAnswersOutsideTheProductionProfile() throws Exception {
        JsonNode result = query(tenantId, "MANAGER", "{ __schema { types { name } } }");

        assertThat(result.has("errors")).isFalse();
        assertThat(result.at("/data/__schema/types").toString()).contains("ActivityLogEntry");
    }

    @Test
    @Tag("REQ-MON-08")
    void onlyThePostEndpointOfTheGraphqlPathIsExposedAndNoUiIsServed() throws Exception {
        mockMvc.perform(get("/graphiql")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mockMvc.perform(get("/graphql")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    // ---- what a client can ask for (REQ-MON-08) ----

    @Test
    @Tag("REQ-MON-08")
    void theClientChoosesWhichFieldsAreReturned() throws Exception {
        seed("LOGIN", actorA, "{\"role\":\"ADMIN\"}", 1);

        JsonNode result = query(tenantId, "ADMIN", "{ activityLog { items { eventType } } }");

        JsonNode entry = result.at("/data/activityLog/items/0");
        assertThat(entry.fieldNames()).toIterable().containsExactly("eventType");
    }

    @Test
    @Tag("REQ-MON-08")
    void aSystemEntryHasNoActorAndAnEmptyMetadataIsAnEmptyObject() throws Exception {
        JournalFixtures.insert(jdbc, tenantId, "ADMIN_DEMOTION_ESCALATED", null, null, JournalFixtures.MONTH_START.plusSeconds(1));

        JsonNode entry = query(tenantId, "ADMIN", "{ activityLog { items { actorUserId metadata } } }").at("/data/activityLog/items/0");

        assertThat(entry.get("actorUserId").isNull()).isTrue();
        assertThat(entry.get("metadata").asText()).isEqualTo("{}");
    }

    // ---- pagination (REQ-MON-04) ----

    @Test
    @Tag("REQ-MON-04")
    void entriesArePaginatedWithTotalsAndPagesDoNotOverlap() throws Exception {
        List<UUID> newestFirst = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            newestFirst.add(0, seed("LOGIN", actorA, "{}", i));
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = query(tenantId, "ADMIN", "{ activityLog(page: " + page + ", size: 10) { page size totalItems totalPages items { id } } }");
            JsonNode data = result.at("/data/activityLog");
            assertThat(data.get("page").asInt()).isEqualTo(page);
            assertThat(data.get("size").asInt()).isEqualTo(10);
            assertThat(data.get("totalItems").asInt()).isEqualTo(25);
            assertThat(data.get("totalPages").asInt()).isEqualTo(3);
            data.get("items").forEach(item -> seen.add(item.get("id").asText()));
        }

        assertThat(seen).hasSize(25).doesNotHaveDuplicates()
                .containsExactlyElementsOf(newestFirst.stream().map(UUID::toString).toList());
    }

    @Test
    @Tag("REQ-MON-04")
    void aPagePastTheEndIsEmptyAndTheDefaultsApplyWhenNothingIsAsked() throws Exception {
        for (int i = 1; i <= 3; i++) {
            seed("LOGIN", actorA, "{}", i);
        }

        JsonNode past = query(tenantId, "ADMIN", "{ activityLog(page: 5, size: 10) { totalItems totalPages items { id } } }").at("/data/activityLog");
        assertThat(past.get("items")).isEmpty();
        assertThat(past.get("totalItems").asInt()).isEqualTo(3);

        JsonNode defaults = query(tenantId, "ADMIN", "{ activityLog { page size totalItems totalPages items { id } } }").at("/data/activityLog");
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(defaults.get("items")).hasSize(3);
        assertThat(defaults.get("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    @Tag("REQ-MON-04")
    void anEmptyJournalIsAnEmptyPageNotAnError() throws Exception {
        JsonNode result = query(tenantId, "ADMIN", "{ activityLog { totalItems totalPages items { id } } }");

        assertThat(result.has("errors")).isFalse();
        assertThat(result.at("/data/activityLog/items")).isEmpty();
        assertThat(result.at("/data/activityLog/totalItems").asInt()).isZero();
        assertThat(result.at("/data/activityLog/totalPages").asInt()).isZero();
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {"page: -1", "size: 0", "size: -5", "size: 101", "size: 100000"})
    @Tag("REQ-MON-04")
    void aPageOrSizeOutsideTheAllowedRangeIsRejected(String arguments) throws Exception {
        JsonNode result = query(tenantId, "ADMIN", "{ activityLog(" + arguments + ") { items { id } } }");

        assertThat(errorClassification(result)).isEqualTo("BAD_REQUEST");
    }

    @Test
    @Tag("REQ-MON-04")
    void theLargestAllowedPageIsAccepted() throws Exception {
        JsonNode result = query(tenantId, "ADMIN", "{ activityLog(size: 100) { size items { id } } }");

        assertThat(result.has("errors")).isFalse();
        assertThat(result.at("/data/activityLog/size").asInt()).isEqualTo(100);
    }

    // ---- filters (REQ-MON-04) ----

    @Test
    @Tag("REQ-MON-04")
    void entriesCanBeFilteredByEventType() throws Exception {
        seed("LOGIN", actorA, "{}", 1);
        UUID role = seed("MEMBER_ROLE_CHANGED", actorA, "{}", 2);
        UUID invite = seed("INVITATION_CREATED", actorA, "{}", 3);

        List<String> one = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { eventTypes: [\"MEMBER_ROLE_CHANGED\"] }) { items { id } } }"));
        List<String> two = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { eventTypes: [\"MEMBER_ROLE_CHANGED\", \"INVITATION_CREATED\"] }) { items { id } } }"));
        List<String> none = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { eventTypes: [\"THEME_CHANGED\"] }) { items { id } } }"));

        assertThat(one).containsExactly(role.toString());
        assertThat(two).containsExactly(invite.toString(), role.toString());
        assertThat(none).isEmpty();
    }

    @Test
    @Tag("REQ-MON-04")
    void entriesCanBeFilteredByActor() throws Exception {
        seed("LOGIN", actorA, "{}", 1);
        UUID byB = seed("LOGIN", actorB, "{}", 2);
        JournalFixtures.insert(jdbc, tenantId, "ADMIN_DEMOTION_ESCALATED", null, "{}", JournalFixtures.MONTH_START.plusSeconds(3));

        List<String> result = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { actorUserId: \"" + actorB + "\" }) { items { id } } }"));

        assertThat(result).containsExactly(byB.toString());
    }

    @Test
    @Tag("REQ-MON-04")
    void entriesCanBeFilteredByADateWindowFromInclusiveToExclusive() throws Exception {
        UUID h1 = seed("LOGIN", actorA, "{}", 1);
        UUID h2 = seed("LOGIN", actorA, "{}", 2);
        UUID h3 = seed("LOGIN", actorA, "{}", 3);
        UUID h4 = seed("LOGIN", actorA, "{}", 4);
        String h2Start = JournalFixtures.MONTH_START.plus(Duration.ofHours(2)).toString();
        String h4Start = JournalFixtures.MONTH_START.plus(Duration.ofHours(4)).toString();

        List<String> window = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { from: \"" + h2Start + "\", to: \"" + h4Start + "\" }) { items { id } } }"));
        List<String> fromOnly = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { from: \"" + h4Start + "\" }) { items { id } } }"));
        List<String> toOnly = ids(query(tenantId, "ADMIN", "{ activityLog(filter: { to: \"" + h2Start + "\" }) { items { id } } }"));

        assertThat(window).containsExactly(h3.toString(), h2.toString());
        assertThat(fromOnly).containsExactly(h4.toString());
        assertThat(toOnly).containsExactly(h1.toString());
    }

    @Test
    @Tag("REQ-MON-04")
    void filtersCombineWithAndAndTheTotalsCountOnlyTheFilteredEntries() throws Exception {
        for (int i = 1; i <= 6; i++) {
            seed(i % 2 == 0 ? "LOGIN" : "THEME_CHANGED", i <= 3 ? actorA : actorB, "{}", i);
        }

        JsonNode result = query(tenantId, "ADMIN", "{ activityLog(page: 0, size: 2, filter: { eventTypes: [\"LOGIN\"], actorUserId: \"" + actorB
                + "\" }) { totalItems totalPages items { eventType actorUserId } } }");

        JsonNode data = result.at("/data/activityLog");
        assertThat(data.get("totalItems").asInt()).isEqualTo(2); // hours 4 and 6
        assertThat(data.get("items")).hasSize(2).allSatisfy(item -> {
            assertThat(item.get("eventType").asText()).isEqualTo("LOGIN");
            assertThat(item.get("actorUserId").asText()).isEqualTo(actorB.toString());
        });
    }

    @ParameterizedTest(name = "filter {0} is rejected")
    @ValueSource(strings = {
            "from: \"yesterday\"", "to: \"2026-13-45T00:00:00Z\"", "from: \"2026-11-02\"",
            "actorUserId: \"not-a-uuid\"", "actorUserId: \"1' OR '1'='1\"",
            "eventTypes: [\"login\"]", "eventTypes: [\"X'; DROP TABLE notif.activity_log;--\"]", "eventTypes: [\"\"]",
            "from: \"2026-11-02T10:00:00Z\", to: \"2026-11-02T09:00:00Z\"",
            "from: \"2026-11-02T10:00:00Z\", to: \"2026-11-02T10:00:00Z\""})
    @Tag("REQ-MON-04")
    void aFilterThatDoesNotParseIsABadRequestNeverAnInternalError(String filter) throws Exception {
        seed("LOGIN", actorA, "{}", 1);

        JsonNode result = query(tenantId, "ADMIN", "{ activityLog(filter: { " + filter + " }) { items { id } } }");

        assertThat(errorClassification(result)).isEqualTo("BAD_REQUEST");
        assertThat(jdbc.queryForObject("select count(*) from notif.activity_log where tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @Tag("REQ-MON-04")
    void moreThanTwentyEventTypesAreRejected() throws Exception {
        String types = String.join(",", java.util.Collections.nCopies(21, "\"LOGIN\""));

        JsonNode result = query(tenantId, "ADMIN", "{ activityLog(filter: { eventTypes: [" + types + "] }) { items { id } } }");

        assertThat(errorClassification(result)).isEqualTo("BAD_REQUEST");
    }

    @Test
    @Tag("REQ-MON-08")
    void anInternalFailureIsReportedWithoutItsDetails() throws Exception {
        jdbc.execute("ALTER TABLE notif.activity_log RENAME TO activity_log_gone");
        try {
            JsonNode result = query(tenantId, "ADMIN", "{ activityLog { items { id } } }");

            assertThat(errorClassification(result)).isEqualTo("INTERNAL_ERROR");
            assertThat(result.toString()).doesNotContain("activity_log", "relation", "SQL", "PSQLException");
        } finally {
            jdbc.execute("ALTER TABLE notif.activity_log_gone RENAME TO activity_log");
        }
    }

    // ---- limits on what a query may cost (REQ-MON-08), default configuration ----

    @Value("${saas.graphql.max-depth}")
    private int maxDepth;
    @Value("${saas.graphql.max-complexity}")
    private int maxComplexity;
    @Value("${saas.graphql.max-page-size}")
    private int maxPageSize;

    @Test
    @Tag("REQ-MON-08")
    void theShippedDefaultsAreADepthOfSixAComplexityOfAThousandAndPagesOfAtMostOneHundred() {
        assertThat(maxDepth).isEqualTo(6);
        assertThat(maxComplexity).isEqualTo(1000);
        assertThat(maxPageSize).isEqualTo(100);
    }

    @Test
    @Tag("REQ-MON-08")
    void theLargestPageSelectingEveryFieldIsServedOnceButNotRepeatedUnderAliases() throws Exception {
        seed("LOGIN", actorA, "{}", 1);
        String maxPage = "activityLog(size: 100) { page size totalItems totalPages items { " + ALL_FIELDS + " } }";

        JsonNode once = query(tenantId, "ADMIN", "{ " + maxPage + " }");
        JsonNode twice = query(tenantId, "ADMIN", "{ first: " + maxPage + " second: " + maxPage + " }");

        assertThat(once.has("errors")).as(once.toString()).isFalse();
        assertThat(twice.has("errors")).isTrue();
        assertThat(twice.at("/errors/0/message").asText()).containsIgnoringCase("complexity");
        assertThat(twice.at("/data").isMissingNode() || twice.at("/data").isNull()).isTrue();
    }

    @Test
    @Tag("REQ-MON-08")
    void aSmallPageCostsLessSoTheSameSelectionIsServedManyTimesAtSizeTwenty() throws Exception {
        seed("LOGIN", actorA, "{}", 1);
        String page = "activityLog(size: 20) { items { " + ALL_FIELDS + " } }"; // 1 + (1 + 20 * 5) = 102

        JsonNode nine = query(tenantId, "ADMIN", "{ " + java.util.stream.IntStream.range(0, 9).mapToObj(i -> "p" + i + ": " + page)
                .collect(java.util.stream.Collectors.joining(" ")) + " }");
        JsonNode eleven = query(tenantId, "ADMIN", "{ " + java.util.stream.IntStream.range(0, 11).mapToObj(i -> "p" + i + ": " + page)
                .collect(java.util.stream.Collectors.joining(" ")) + " }");

        assertThat(nine.has("errors")).as(nine.toString()).isFalse();
        assertThat(eleven.has("errors")).isTrue();
    }

    @Test
    @Tag("REQ-MON-08")
    void manyAliasesOfACheapFieldAreAlsoBoundedByTheComplexityLimit() throws Exception {
        String many = String.join(" ", java.util.stream.IntStream.range(0, 400)
                .mapToObj(i -> "a" + i + ": activityLog { totalItems totalPages page size }").toList());

        JsonNode result = query(tenantId, "ADMIN", "{ " + many + " }");

        assertThat(result.has("errors")).isTrue();
        assertThat(result.at("/errors/0/message").asText()).containsIgnoringCase("complexity");
    }

    // ---- helpers ----

    private UUID seed(String eventType, UUID actor, String metadataJson, int hour) {
        return JournalFixtures.insert(jdbc, tenantId, eventType, actor, metadataJson, JournalFixtures.MONTH_START.plus(Duration.ofHours(hour)));
    }

    private static List<String> ids(JsonNode result) {
        assertThat(result.has("errors")).as(result.toString()).isFalse();
        List<String> ids = new ArrayList<>();
        result.at("/data/activityLog/items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static String errorClassification(JsonNode result) {
        assertThat(result.has("errors")).as(result.toString()).isTrue();
        return result.at("/errors/0/extensions/classification").asText();
    }

    private String body(String query) throws Exception {
        return objectMapper.writeValueAsString(Map.of("query", query));
    }

    private JsonNode query(UUID tenant, String role, String query) throws Exception {
        var request = post(GRAPHQL).contentType("application/json").content(body(query))
                .header("X-User-Id", admin.toString()).header("X-Tenant-Id", tenant.toString()).header("X-Role", role);
        return execute(request);
    }

    private JsonNode execute(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
