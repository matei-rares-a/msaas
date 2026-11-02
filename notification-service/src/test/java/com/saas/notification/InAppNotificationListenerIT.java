package com.saas.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.inapp.InAppNotificationListener;
import com.saas.notification.inapp.OrgDirectoryStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.saas.testsupport.containers.PostgresTestContainer;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Notifications created from the domain events (REQ-NOTIF-03, REQ-NOTIF-01) with the real consumer, the real
 * directory client and a stand-in for organization-service. Events are handed to the consumer as the JSON an
 * outbox relay would publish (a test below checks the topics and the group the consumer is registered with); the point
 * here is what each one does to the table, and that a redelivery or a failing directory does not
 * lose or repeat anything (REQ-EVT-05). The shared test Postgres user is a superuser, so tenant isolation
 * proved here is the explicit tenant on each row.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false",
        // ProcessedEventStore uses an unqualified table name; the shared test Postgres lacks the role's search_path
        "spring.datasource.hikari.connection-init-sql=SET search_path TO notif,public"
})
class InAppNotificationListenerIT implements PostgresTestContainer {

    private static final OrgDirectoryStub org = new OrgDirectoryStub();

    @AfterAll
    static void stopOrg() {
        org.close();
    }

    @DynamicPropertySource
    static void orgUrl(DynamicPropertyRegistry registry) {
        registry.add("org-service.base-url", org::baseUrl);
    }

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private InAppNotificationListener listener;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant;
    private UUID admin;
    private UUID manager;
    private UUID user;

    @BeforeEach
    void freshTenant() {
        org.recover();
        org.clearRequests();
        tenant = UUID.randomUUID();
        admin = UUID.randomUUID();
        manager = UUID.randomUUID();
        user = UUID.randomUUID();
        org.member(tenant, admin, "ADMIN");
        org.member(tenant, manager, "MANAGER");
        org.member(tenant, user, "USER");
    }

    // ---- who is told about what (REQ-NOTIF-03) ----

    @Test
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-NOTIF-01")
    void aRoleChangeNotifiesTheAffectedUserInThatTenantAndNobodyElse() throws Exception {
        deliver("MEMBER_ROLE_CHANGED", tenant, Map.of("tenantId", tenant, "actorUserId", admin, "targetUserId", user,
                "previousRole", "USER", "newRole", "MANAGER"));

        var rows = notificationsIn(tenant);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("user_id", user).containsEntry("tenant_id", tenant)
                .containsEntry("event_type", "MEMBER_ROLE_CHANGED").containsEntry("title", "Your role changed")
                .containsEntry("level", "INFO").containsEntry("is_read", false);
        assertThat(rows.get(0).get("message")).isEqualTo("Your role in this organization changed from USER to MANAGER.");
        assertThat(rows.get(0).get("created_at")).isNotNull();
        assertThat(org.requests()).as("the target is in the event, nobody needs to be looked up").isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void aDemotionRequestNotifiesTheAdminAskedToAcceptIt() throws Exception {
        deliver("ADMIN_DEMOTION_REQUESTED", tenant, Map.of("tenantId", tenant, "requestId", UUID.randomUUID(),
                "actorUserId", admin, "targetUserId", manager, "newRole", "USER"));

        var rows = notificationsIn(tenant);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("user_id", manager).containsEntry("level", "WARNING")
                .containsEntry("event_type", "ADMIN_DEMOTION_REQUESTED");
        assertThat(rows.get(0).get("message").toString()).contains("change your role to USER");
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void anAcceptedInvitationNotifiesTheAdminsAndManagersButNotUsersNorTheOneWhoJoined() throws Exception {
        UUID joinedManager = UUID.randomUUID();
        org.member(tenant, joinedManager, "MANAGER");

        deliver("INVITATION_ACCEPTED", tenant, Map.of("invitationId", UUID.randomUUID(), "tenantId", tenant,
                "membershipId", UUID.randomUUID(), "userId", joinedManager, "role", "MANAGER"));

        assertThat(recipients(tenant)).containsExactlyInAnyOrder(admin, manager);
        assertThat(org.requests()).containsExactly("/internal/tenants/" + tenant + "/members?roles=ADMIN,MANAGER");
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void aSubscriptionThatBecamePastDueNotifiesTheAdminsAndManagersOnce() throws Exception {
        deliver("SUBSCRIPTION_CHANGED", tenant, Map.of("tenantId", tenant, "planId", UUID.randomUUID(), "maxUsers", 5,
                "status", "PAST_DUE", "previousStatus", "ACTIVE"));
        deliver("SUBSCRIPTION_CHANGED", tenant, Map.of("tenantId", tenant, "planId", UUID.randomUUID(), "maxUsers", 5,
                "status", "PAST_DUE", "previousStatus", "PAST_DUE")); // the next failed attempt: nothing new

        assertThat(recipients(tenant)).containsExactlyInAnyOrder(admin, manager);
        assertThat(notificationsIn(tenant)).allSatisfy(row -> assertThat(row)
                .containsEntry("event_type", "SUBSCRIPTION_PAST_DUE").containsEntry("level", "WARNING"));
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void anExpiringTrialNotifiesTheAdminsAndManagersWithTheEndDate() throws Exception {
        deliverRaw("TRIAL_EXPIRING", tenant, "{\"tenantId\":\"" + tenant + "\",\"subscriptionId\":\"" + UUID.randomUUID()
                + "\",\"trialEndsAt\":" + Instant.parse("2026-11-16T09:30:00Z").getEpochSecond() + ".5}");

        assertThat(recipients(tenant)).containsExactlyInAnyOrder(admin, manager);
        assertThat(notificationsIn(tenant)).allSatisfy(row -> {
            assertThat(row.get("event_type")).isEqualTo("TRIAL_EXPIRING");
            assertThat(row.get("message").toString()).contains("2026-11-16");
        });
    }

    @Test
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-NOTIF-01")
    void eachTenantsEventsNotifyOnlyThatTenantsMembers() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherAdmin = UUID.randomUUID();
        org.member(otherTenant, otherAdmin, "ADMIN");

        deliver("SUBSCRIPTION_CHANGED", otherTenant, Map.of("tenantId", otherTenant, "status", "PAST_DUE", "previousStatus", "ACTIVE"));

        assertThat(recipients(otherTenant)).containsExactly(otherAdmin);
        assertThat(notificationsIn(tenant)).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void eventsThatAreNotOnTheListAndEventsWithNoTenantCreateNothing() throws Exception {
        deliver("LOGIN", tenant, Map.of("tenantId", tenant, "userId", user, "role", "USER"));
        deliver("THEME_CHANGED", tenant, Map.of("tenantId", tenant, "actorUserId", admin, "paletteId", "forest"));
        deliver("MEMBER_OFFBOARDED", tenant, Map.of("tenantId", tenant, "userId", user));
        deliver("MEMBER_ROLE_CHANGED", null, Map.of("targetUserId", user, "previousRole", "USER", "newRole", "ADMIN"));
        listener.onEvent("{\"eventType\":null}");

        assertThat(notificationsIn(tenant)).isEmpty();
        assertThat(org.requests()).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void anIdentityLevelEventIsNeverANotification() throws Exception {
        deliver("SEND_PASSWORD_CHANGED_CONFIRMATION", null, Map.of("userId", user, "email", "someone@example.com"));
        deliver("USER_REGISTERED", null, Map.of("userId", user));

        assertThat(jdbc.queryForObject("select count(*) from notif.in_app_notifications where user_id = ?", Integer.class, user)).isZero();
    }

    // ---- delivery guarantees (REQ-EVT-05, REQ-EVT-07) ----

    @Test
    @Tag("REQ-EVT-05")
    @Tag("REQ-NOTIF-03")
    void anEventDeliveredTwiceNotifiesOnce() throws Exception {
        String event = envelope("INVITATION_ACCEPTED", tenant, UUID.randomUUID(), Map.of("invitationId", UUID.randomUUID(),
                "userId", UUID.randomUUID(), "role", "USER"));

        listener.onEvent(event);
        listener.onEvent(event);

        assertThat(notificationsIn(tenant)).hasSize(2); // admin and manager, once each
        assertThat(recipients(tenant)).containsExactlyInAnyOrder(admin, manager);
    }

    @Test
    @Tag("REQ-EVT-05")
    @Tag("REQ-EVT-07")
    void whenTheDirectoryIsDownTheEventFailsAndATryAfterItRecoversStillNotifies() throws Exception {
        String event = envelope("TRIAL_EXPIRING", tenant, UUID.randomUUID(), Map.of("tenantId", tenant, "trialEndsAt", "2026-11-16T00:00:00Z"));
        org.failWith(503);

        assertThatThrownBy(() -> listener.onEvent(event)).isInstanceOf(IllegalStateException.class);
        assertThat(notificationsIn(tenant)).as("nothing half-done").isEmpty();

        org.recover();
        listener.onEvent(event);

        assertThat(recipients(tenant)).containsExactlyInAnyOrder(admin, manager);
    }

    @Test
    @Tag("REQ-EVT-07")
    void aMalformedEventFailsAndCreatesNothing() {
        String event = envelope("MEMBER_ROLE_CHANGED", tenant, UUID.randomUUID(), Map.of("previousRole", "USER", "newRole", "MANAGER"));

        assertThatThrownBy(() -> listener.onEvent(event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.onEvent("{not json")).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThat(notificationsIn(tenant)).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-03")
    void aTenantWithNoAdminsOrManagersCreatesNothingAndIsNotAnError() throws Exception {
        UUID empty = UUID.randomUUID();

        deliver("TRIAL_EXPIRING", empty, Map.of("tenantId", empty, "trialEndsAt", "2026-11-16T00:00:00Z"));

        assertThat(notificationsIn(empty)).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-01")
    void noNotificationHoldsAnEmailAddress() throws Exception {
        deliver("INVITATION_ACCEPTED", tenant, Map.of("invitationId", UUID.randomUUID(), "userId", UUID.randomUUID(),
                "role", "USER", "email", "private.person@example.com"));
        deliver("SUBSCRIPTION_CHANGED", tenant, Map.of("status", "PAST_DUE", "previousStatus", "ACTIVE", "email", "private.person@example.com"));

        assertThat(jdbc.queryForObject("select count(*) from notif.in_app_notifications where tenant_id = ? and row_to_json(in_app_notifications)::text like '%@%'",
                Integer.class, tenant)).isZero();
    }

    // ---- wiring (the topics and the group the consumer reads with) ----

    @Autowired
    private org.springframework.kafka.config.KafkaListenerEndpointRegistry registry;

    @Test
    @Tag("REQ-NOTIF-03")
    @Tag("REQ-EVT-05")
    void theConsumerReadsTheOrgAndBillingTopicsInItsOwnGroupSoNoOtherListenerSplitsThePartitionsWithIt() {
        var containers = registry.getAllListenerContainers().stream()
                .filter(container -> "notif-service-in-app".equals(container.getGroupId())).toList();

        assertThat(containers).hasSize(1);
        var topics = ((org.springframework.kafka.listener.AbstractMessageListenerContainer<?, ?>) containers.get(0))
                .getContainerProperties().getTopics();
        assertThat(topics).containsExactlyInAnyOrder("org.events", "billing.events");
        assertThat(registry.getAllListenerContainers().stream().map(c -> c.getGroupId()).toList())
                .as("every listener of this service has a group of its own").doesNotHaveDuplicates();
    }

    // ---- helpers ----

    private void deliver(String type, UUID tenantId, Map<String, Object> payload) throws Exception {
        listener.onEvent(envelope(type, tenantId, UUID.randomUUID(), payload));
    }

    private void deliverRaw(String type, UUID tenantId, String payloadJson) throws Exception {
        listener.onEvent("{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"version\":1,\"tenantId\":\""
                + tenantId + "\",\"occurredAt\":1.7E9,\"payload\":" + payloadJson + "}");
    }

    private String envelope(String type, UUID tenantId, UUID eventId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type);
        envelope.put("version", 1);
        if (tenantId != null) {
            envelope.put("tenantId", tenantId);
        }
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Map<String, Object>> notificationsIn(UUID tenantId) {
        return jdbc.queryForList("select user_id, tenant_id, event_type, title, message, level, is_read, created_at"
                + " from notif.in_app_notifications where tenant_id = ? order by user_id", tenantId);
    }

    private List<UUID> recipients(UUID tenantId) {
        return notificationsIn(tenantId).stream().map(row -> (UUID) row.get("user_id")).toList();
    }
}
