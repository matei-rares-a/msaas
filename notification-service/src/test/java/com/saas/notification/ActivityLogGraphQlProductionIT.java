package com.saas.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogPartitionMaintenance;
import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The journal API under the production profile (REQ-MON-08): the schema cannot be discovered through
 * introspection, and a query is refused when it is too deep or too expensive. The depth limit is lowered to
 * 2 here so that the schema's real nesting (page, items, field = 3) crosses it - with the default limit
 * the non-recursive schema cannot be nested that far, which is why the default is asserted separately.
 * The complexity limit keeps its default: the same selection repeated under many aliases is refused.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false",
        "saas.graphql.max-depth=2"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ActivityLogGraphQlProductionIT implements PostgresTestContainer {

    private static final String GRAPHQL = "/notifications/graphql";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ActivityLogPartitionMaintenance partitions;

    private UUID tenantId;

    @BeforeEach
    void freshTenant() {
        partitions.ensureUpcomingPartitions();
        tenantId = UUID.randomUUID();
        JournalFixtures.insert(jdbc, tenantId, "LOGIN", UUID.randomUUID(), "{}", JournalFixtures.MONTH_START.plus(Duration.ofHours(1)));
    }

    @Test
    @Tag("REQ-MON-08")
    void introspectionIsOffInProductionSoTheSchemaCannotBeEnumerated() throws Exception {
        for (String query : List.of("{ __schema { types { name } } }", "{ __type(name: \"ActivityLogEntry\") { fields { name } } }")) {
            JsonNode result = query(query);

            assertThat(result.has("errors")).as(query).isTrue();
            assertThat(result.at("/data").isMissingNode() || result.at("/data").isNull()).as(query).isTrue();
            assertThat(result.toString()).as(query).doesNotContain("ActivityLogEntry\"").doesNotContain("eventType");
        }
    }

    @Test
    @Tag("REQ-MON-08")
    void theQueriesTheApplicationIsBuiltAgainstStillWorkInProduction() throws Exception {
        JsonNode result = query("{ activityLog { totalItems } }");

        assertThat(result.has("errors")).isFalse();
        assertThat(result.at("/data/activityLog/totalItems").asInt()).isEqualTo(1);
    }

    @Test
    @Tag("REQ-MON-08")
    void aQueryNestedDeeperThanTheLimitIsRefusedAndOneWithinItIsServed() throws Exception {
        JsonNode tooDeep = query("{ activityLog { items { id } } }");
        JsonNode within = query("{ activityLog { totalItems } }");

        assertThat(tooDeep.has("errors")).isTrue();
        assertThat(tooDeep.at("/errors/0/message").asText()).containsIgnoringCase("depth");
        assertThat(tooDeep.at("/data").isMissingNode() || tooDeep.at("/data").isNull()).isTrue();
        assertThat(within.has("errors")).isFalse();
    }

    @Test
    @Tag("REQ-MON-08")
    void theSameExpensiveSelectionRepeatedUnderAliasesIsRefusedWhileOnceItIsServed() throws Exception {
        // fields that stay within the lowered depth, so only the aliasing can trip the limit
        String cheap = "a: activityLog { totalItems }";
        assertThat(query("{ " + cheap + " }").has("errors")).isFalse();

        String many = String.join(" ", java.util.stream.IntStream.range(0, 400)
                .mapToObj(i -> "a" + i + ": activityLog { totalItems totalPages page size }").toList());
        JsonNode result = query("{ " + many + " }");

        assertThat(result.has("errors")).isTrue();
        assertThat(result.at("/errors/0/message").asText()).containsIgnoringCase("complexity");
    }

    private JsonNode query(String query) throws Exception {
        var request = post(GRAPHQL).contentType("application/json").content(objectMapper.writeValueAsString(Map.of("query", query)))
                .header("X-User-Id", UUID.randomUUID().toString()).header("X-Tenant-Id", tenantId.toString()).header("X-Role", "ADMIN");
        MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mockMvc.perform(asyncDispatch(result)).andReturn();
        }
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
