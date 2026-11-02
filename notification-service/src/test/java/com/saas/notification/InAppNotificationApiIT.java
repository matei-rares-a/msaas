package com.saas.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Reading and marking one's own in-app notifications (REQ-NOTIF-01, REQ-NOTIF-02, REQ-NOTIF-04) through MockMvc
 * with the identity headers the gateway would set from the token. The shared test Postgres user is a superuser
 * and bypasses RLS, so what is proved about isolation here is the tenant and user filters in the queries.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class InAppNotificationApiIT implements PostgresTestContainer {

    private static final String API = "/notifications/in-app";
    // recent enough that the retention sweep (another test class, same database) never has a reason to touch these rows
    private static final Instant BASE = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).minus(java.time.Duration.ofDays(10));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenantA;
    private UUID tenantB;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void freshIdentities() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
    }

    // ---- listing: only my own, only in this tenant (REQ-NOTIF-01) ----

    @Test
    @Tag("REQ-NOTIF-01")
    @Tag("REQ-NOTIF-04")
    void iSeeMyNotificationsNewestFirstWithEveryFieldAndTheUnreadTotal() throws Exception {
        UUID older = insert(tenantA, alice, "Older", 1, false, "MEMBER_ROLE_CHANGED");
        UUID newer = insert(tenantA, alice, "Newer", 2, false, "TRIAL_EXPIRING");
        insert(tenantA, alice, "Read one", 0, true, "INVITATION_ACCEPTED");

        MvcResult result = list(tenantA, alice, "USER", "");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode page = body(result);
        assertThat(page.get("unreadCount").asLong()).isEqualTo(2);
        assertThat(page.get("totalItems").asLong()).isEqualTo(3);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        JsonNode items = page.get("items");
        assertThat(ids(items)).containsExactly(newer.toString(), older.toString(), idOfTitle(alice, tenantA, "Read one").toString());
        assertThat(items.get(0).get("title").asText()).isEqualTo("Newer");
        assertThat(items.get(0).get("message").asText()).isEqualTo("Message of Newer");
        assertThat(items.get(0).get("level").asText()).isEqualTo("INFO");
        assertThat(items.get(0).get("eventType").asText()).isEqualTo("TRIAL_EXPIRING");
        assertThat(items.get(0).get("read").asBoolean()).isFalse();
        assertThat(Instant.parse(items.get(0).get("createdAt").asText())).isEqualTo(BASE.plusSeconds(2 * 3600));
        assertThat(items.get(2).get("read").asBoolean()).isTrue();
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void iNeverSeeAnotherUsersNotificationsEvenInTheSameTenant() throws Exception {
        insert(tenantA, alice, "For Alice", 1, false, null);
        insert(tenantA, bob, "For Bob", 2, false, null);

        JsonNode page = body(list(tenantA, alice, "ADMIN", ""));

        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("title").asText()).isEqualTo("For Alice");
        assertThat(page.get("totalItems").asLong()).isEqualTo(1);
        assertThat(page.get("unreadCount").asLong()).isEqualTo(1);
    }

    @Test
    @Tag("REQ-NOTIF-01")
    @Tag("REQ-DATA-03")
    void theSameUserInTwoTenantsOnlySeesTheTenantOfTheirToken() throws Exception {
        insert(tenantA, alice, "In A", 1, false, null);
        insert(tenantB, alice, "In B", 2, false, null);

        JsonNode inA = body(list(tenantA, alice, "USER", ""));
        JsonNode inB = body(list(tenantB, alice, "USER", ""));

        assertThat(inA.get("items")).hasSize(1);
        assertThat(inA.get("items").get(0).get("title").asText()).isEqualTo("In A");
        assertThat(inB.get("items")).hasSize(1);
        assertThat(inB.get("items").get(0).get("title").asText()).isEqualTo("In B");
        assertThat(count(tenantA, alice, "USER")).isEqualTo(1);
        assertThat(count(tenantB, alice, "USER")).isEqualTo(1);
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void aQueryParameterCannotNameAnotherUserOrTenant() throws Exception {
        insert(tenantA, alice, "For Alice", 1, false, null);
        insert(tenantA, bob, "For Bob", 2, false, null);
        insert(tenantB, alice, "Alice in B", 3, false, null);

        JsonNode page = body(list(tenantA, alice, "USER", "?userId=" + bob + "&tenantId=" + tenantB + "&user_id=" + bob));

        assertThat(titles(page.get("items"))).containsExactly("For Alice");
    }

    @Test
    @Tag("REQ-NOTIF-04")
    void anEmptyInboxIsAnEmptyPage() throws Exception {
        JsonNode page = body(list(tenantA, alice, "USER", ""));

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("totalItems").asLong()).isZero();
        assertThat(page.get("unreadCount").asLong()).isZero();
        assertThat(count(tenantA, alice, "USER")).isZero();
    }

    // ---- paging (REQ-NOTIF-04) ----

    @Test
    @Tag("REQ-NOTIF-04")
    void pagesDoNotOverlapAndAPagePastTheEndIsEmpty() throws Exception {
        List<String> newestFirst = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            newestFirst.add(0, insert(tenantA, alice, "N" + i, i, false, null).toString());
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode result = body(list(tenantA, alice, "USER", "?page=" + page + "&size=10"));
            assertThat(result.get("totalItems").asLong()).isEqualTo(25);
            seen.addAll(ids(result.get("items")));
        }

        assertThat(seen).containsExactlyElementsOf(newestFirst);
        assertThat(body(list(tenantA, alice, "USER", "?page=3&size=10")).get("items")).isEmpty();
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=-3", "?size=101", "?size=100000", "?page=abc", "?size=1.5"})
    @Tag("REQ-NOTIF-04")
    void aPageOrSizeOutsideTheAllowedRangeIsABadRequest(String query) throws Exception {
        assertThat(list(tenantA, alice, "USER", query).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-NOTIF-04")
    void theLargestAllowedPageIsAccepted() throws Exception {
        assertThat(list(tenantA, alice, "USER", "?size=100").getResponse().getStatus()).isEqualTo(200);
    }

    // ---- unread count (REQ-NOTIF-04) ----

    @Test
    @Tag("REQ-NOTIF-04")
    void theUnreadCountIsMineInThisTenantAndNothingIsCached() throws Exception {
        insert(tenantA, alice, "u1", 1, false, null);
        insert(tenantA, alice, "u2", 2, false, null);
        insert(tenantA, alice, "r1", 3, true, null);
        insert(tenantA, bob, "other user", 4, false, null);
        insert(tenantB, alice, "other tenant", 5, false, null);

        MvcResult result = mockMvc.perform(identity(get(API + "/unread-count"), tenantA, alice, "USER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body(result).get("unreadCount").asLong()).isEqualTo(2);
    }

    // ---- marking as read ----

    @Test
    @Tag("REQ-NOTIF-04")
    void markingOneAsReadChangesOnlyThatOneAndDoingItAgainChangesNothing() throws Exception {
        UUID first = insert(tenantA, alice, "first", 1, false, null);
        UUID second = insert(tenantA, alice, "second", 2, false, null);

        MvcResult marked = markRead(tenantA, alice, "USER", first);
        MvcResult again = markRead(tenantA, alice, "USER", first);

        assertThat(marked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(marked).get("read").asBoolean()).isTrue();
        assertThat(body(marked).get("id").asText()).isEqualTo(first.toString());
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(isRead(first)).isTrue();
        assertThat(isRead(second)).isFalse();
        assertThat(count(tenantA, alice, "USER")).isEqualTo(1);
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void aNotificationThatIsNotMineIsNotFoundWhetherItIsAnotherUsersOrAnotherTenantsOrDoesNotExist() throws Exception {
        UUID bobs = insert(tenantA, bob, "Bob's", 1, false, null);
        UUID alicesInB = insert(tenantB, alice, "Alice in B", 2, false, null);

        for (UUID id : List.of(bobs, alicesInB, UUID.randomUUID())) {
            MvcResult result = markRead(tenantA, alice, "USER", id);
            assertThat(result.getResponse().getStatus()).as(id.toString()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Bob's").doesNotContain("Alice in B");
        }
        assertThat(isRead(bobs)).isFalse();
        assertThat(isRead(alicesInB)).isFalse();
    }

    @Test
    @Tag("REQ-NOTIF-04")
    void aNotificationIdThatIsNotAnIdIsABadRequest() throws Exception {
        MvcResult result = mockMvc.perform(identity(patch(API + "/not-an-id/read"), tenantA, alice, "USER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Tag("REQ-NOTIF-04")
    @Tag("REQ-NOTIF-01")
    void markingAllAsReadTouchesOnlyMyNotificationsInThisTenant() throws Exception {
        UUID mine1 = insert(tenantA, alice, "m1", 1, false, null);
        UUID mine2 = insert(tenantA, alice, "m2", 2, false, null);
        insert(tenantA, alice, "already read", 3, true, null);
        UUID bobs = insert(tenantA, bob, "bob", 4, false, null);
        UUID inB = insert(tenantB, alice, "alice in B", 5, false, null);

        MvcResult result = mockMvc.perform(identity(patch(API + "/read"), tenantA, alice, "MANAGER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("updated").asInt()).isEqualTo(2);
        assertThat(isRead(mine1)).isTrue();
        assertThat(isRead(mine2)).isTrue();
        assertThat(isRead(bobs)).as("another user's").isFalse();
        assertThat(isRead(inB)).as("the same user in another tenant").isFalse();
        assertThat(count(tenantA, alice, "MANAGER")).isZero();
        assertThat(count(tenantB, alice, "MANAGER")).isEqualTo(1);
    }

    // ---- who may call (REQ-AUTH-05) ----

    @ParameterizedTest(name = "role {0} can read its own notifications")
    @ValueSource(strings = {"ADMIN", "MANAGER", "USER"})
    @Tag("REQ-NOTIF-01")
    void everyRoleCanReadAndMarkItsOwn(String role) throws Exception {
        UUID id = insert(tenantA, alice, "mine", 1, false, null);

        assertThat(list(tenantA, alice, role, "").getResponse().getStatus()).isEqualTo(200);
        assertThat(markRead(tenantA, alice, role, id).getResponse().getStatus()).isEqualTo(200);
    }

    @ParameterizedTest(name = "role [{0}] is refused")
    @ValueSource(strings = {"READER", "admin", "ROLE_USER", "SUPERUSER", ""})
    @Tag("REQ-NOTIF-01")
    void aRoleTheGatewayDoesNotIssueIsRefused(String role) throws Exception {
        insert(tenantA, alice, "mine", 1, false, null);

        MvcResult result = list(tenantA, alice, role, "");

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("mine");
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"X-User-Id", "X-Tenant-Id", "X-Role"})
    @Tag("REQ-NOTIF-01")
    void aRequestWithoutTheGatewaysIdentityIsUnauthenticated(String missing) throws Exception {
        insert(tenantA, alice, "mine", 1, false, null);
        MockHttpServletRequestBuilder request = get(API);
        if (!missing.equals("X-User-Id")) {
            request.header("X-User-Id", alice.toString());
        }
        if (!missing.equals("X-Tenant-Id")) {
            request.header("X-Tenant-Id", tenantA.toString());
        }
        if (!missing.equals("X-Role")) {
            request.header("X-Role", "USER");
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("mine");
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void aMalformedIdentityIsUnauthenticated() throws Exception {
        MvcResult result = mockMvc.perform(get(API).header("X-User-Id", "alice").header("X-Tenant-Id", tenantA.toString())
                .header("X-Role", "USER")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    // ---- only the system creates notifications (REQ-NOTIF-02) ----

    @Test
    @Tag("REQ-NOTIF-02")
    void noRequestCanCreateANotificationWhateverTheMethodOrPath() throws Exception {
        long before = total();
        String body = "{\"userId\":\"" + alice + "\",\"title\":\"Phishing\",\"message\":\"Click here\",\"level\":\"INFO\"}";

        for (String path : List.of(API, API + "/read", API + "/" + UUID.randomUUID(), API + "/" + UUID.randomUUID() + "/read", API + "/unread-count")) {
            for (MockHttpServletRequestBuilder request : List.of(post(path), put(path), post(path).contentType("application/json").content(body))) {
                MvcResult result = mockMvc.perform(identity(request, tenantA, alice, "ADMIN")).andReturn();
                assertThat(result.getResponse().getStatus()).as(path).isIn(400, 404, 405, 415);
            }
        }
        for (String path : List.of(API, API + "/unread-count")) {
            MvcResult result = mockMvc.perform(identity(patch(path).contentType("application/json").content(body), tenantA, alice, "ADMIN")).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isIn(400, 404, 405, 415);
        }

        assertThat(total()).isEqualTo(before);
    }

    // ---- helpers ----

    private MvcResult list(UUID tenant, UUID user, String role, String query) throws Exception {
        return mockMvc.perform(identity(get(API + query), tenant, user, role)).andReturn();
    }

    private MvcResult markRead(UUID tenant, UUID user, String role, UUID id) throws Exception {
        return mockMvc.perform(identity(patch(API + "/" + id + "/read"), tenant, user, role)).andReturn();
    }

    private static MockHttpServletRequestBuilder identity(MockHttpServletRequestBuilder request, UUID tenant, UUID user, String role) {
        return request.header("X-User-Id", user.toString()).header("X-Tenant-Id", tenant.toString()).header("X-Role", role);
    }

    private long count(UUID tenant, UUID user, String role) throws Exception {
        return body(mockMvc.perform(identity(get(API + "/unread-count"), tenant, user, role)).andReturn()).get("unreadCount").asLong();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static List<String> titles(JsonNode items) {
        List<String> titles = new ArrayList<>();
        items.forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private UUID insert(UUID tenant, UUID user, String title, int hoursAfterBase, boolean read, String eventType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notif.in_app_notifications (id, tenant_id, user_id, title, message, level, is_read, event_type, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'INFO', ?, ?, ?)", id, tenant, user, title, "Message of " + title, read, eventType,
                Timestamp.from(BASE.plusSeconds(hoursAfterBase * 3600L)));
        return id;
    }

    private UUID idOfTitle(UUID user, UUID tenant, String title) {
        return jdbc.queryForObject("select id from notif.in_app_notifications where user_id = ? and tenant_id = ? and title = ?",
                UUID.class, user, tenant, title);
    }

    private boolean isRead(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select is_read from notif.in_app_notifications where id = ?", Boolean.class, id));
    }

    private long total() {
        return jdbc.queryForObject("select count(*) from notif.in_app_notifications", Long.class);
    }
}
