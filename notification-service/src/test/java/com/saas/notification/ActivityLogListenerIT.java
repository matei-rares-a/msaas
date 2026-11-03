package com.saas.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogPartitionMaintenance;
import com.saas.shared.events.ActivityEventCatalog;
import com.saas.testsupport.containers.KafkaTestContainer;
import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.containers.SharedContainers;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The tenant activity journal is written from the real domain events over Kafka (REQ-MON-01): every
 * audited action in the catalog becomes one entry, in the right tenant, with who did it and only the
 * catalog's metadata; anything else - identity and password events, unlisted types, a tenant-less
 * event - never does (REQ-PWD-08), and a redelivery writes nothing twice (REQ-EVT-05). Shares its
 * context with {@link MemberOffboardedListenerIT} (same properties), which reads {@code org.events} too.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // the shared test Postgres is used as a superuser, so the role's search_path (set by the real
        // topology) is missing; ProcessedEventStore uses an unqualified table name
        "spring.datasource.hikari.connection-init-sql=SET search_path TO notif,public",
        // a brand-new consumer group would otherwise start after whatever the topic already holds
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class ActivityLogListenerIT implements PostgresTestContainer, KafkaTestContainer {

    static {
        KafkaTestTopics.ensureListenedTopicsExist();
    }

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID INVITATION = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID PLAN = UUID.fromString("00000000-0000-0000-0000-0000000000e5");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ActivityLogPartitionMaintenance partitions;

    @BeforeEach
    void partitionsExist() {
        partitions.ensureUpcomingPartitions();
    }

    /** A realistic payload per audited event, as the producing service publishes it (checked by its own tests). */
    static Map<String, Map<String, Object>> samples(UUID tenantId) {
        Map<String, Map<String, Object>> samples = new LinkedHashMap<>();
        samples.put("LOGIN", Map.of("tenantId", tenantId, "userId", ACTOR, "role", "ADMIN"));
        samples.put("MEMBER_ROLE_CHANGED", Map.of("tenantId", tenantId, "actorUserId", ACTOR, "targetUserId", TARGET,
                "previousRole", "USER", "newRole", "MANAGER"));
        samples.put("ADMIN_DEMOTION_REQUESTED", Map.of("tenantId", tenantId, "requestId", REQUEST, "actorUserId", ACTOR,
                "targetUserId", TARGET, "newRole", "MANAGER"));
        samples.put("ADMIN_DEMOTION_ACCEPTED", Map.of("tenantId", tenantId, "requestId", REQUEST, "actorUserId", TARGET, "newRole", "MANAGER"));
        samples.put("ADMIN_DEMOTION_REJECTED", Map.of("tenantId", tenantId, "requestId", REQUEST, "actorUserId", TARGET, "newRole", "MANAGER"));
        samples.put("ADMIN_DEMOTION_ESCALATED", Map.of("requestId", REQUEST, "targetUserId", TARGET, "requestedBy", ACTOR));
        samples.put("INVITATION_CREATED", Map.of("tenantId", tenantId, "invitationId", INVITATION, "actorUserId", ACTOR, "role", "USER"));
        samples.put("INVITATION_REVOKED", Map.of("tenantId", tenantId, "invitationId", INVITATION, "actorUserId", ACTOR, "role", "USER"));
        samples.put("INVITATION_ACCEPTED", Map.of("invitationId", INVITATION, "tenantId", tenantId, "membershipId", UUID.randomUUID(),
                "userId", ACTOR, "role", "USER"));
        samples.put("SUBSCRIPTION_CHANGED", Map.of("tenantId", tenantId, "planId", PLAN, "maxUsers", 25, "status", "ACTIVE", "actorUserId", ACTOR));
        samples.put("THEME_CHANGED", Map.of("tenantId", tenantId, "actorUserId", ACTOR, "paletteId", "forest"));
        Map<String, Object> logo = new LinkedHashMap<>();
        logo.put("tenantId", tenantId);
        logo.put("actorUserId", ACTOR);
        logo.put("paletteId", null);
        samples.put("LOGO_CHANGED", logo);
        samples.put("MODULE_CHANGED", Map.of("tenantId", tenantId, "actorUserId", ACTOR, "module", "LEAVE", "enabled", true));
        for (String leaveEvent : List.of("LEAVE_APPROVED", "LEAVE_REJECTED", "LEAVE_AUTO_APPROVED", "LEAVE_CANCELLED")) {
            samples.put(leaveEvent, Map.of("tenantId", tenantId, "requestId", REQUEST, "actorUserId", ACTOR, "requesterUserId", TARGET,
                    "leaveType", "ODIHNA", "startDate", "2026-12-01", "endDate", "2026-12-05", "days", 5, "previousStatus", "PENDING"));
        }
        samples.put("LEAVE_POLICY_CHANGED", Map.of("tenantId", tenantId, "actorUserId", ACTOR, "change", "BALANCE_OVERRIDE",
                "targetUserId", TARGET, "allowance", 30));
        return samples;
    }

    static Stream<String> everyAuditedEventType() {
        return ActivityEventCatalog.eventTypes().stream().sorted();
    }

    @Test
    @Tag("REQ-MON-01")
    void theSamplesCoverEveryEventTheCatalogLists() {
        assertThat(samples(UUID.randomUUID()).keySet()).containsExactlyInAnyOrderElementsOf(ActivityEventCatalog.eventTypes());
    }

    @ParameterizedTest(name = "{0} is journaled")
    @MethodSource("everyAuditedEventType")
    @Tag("REQ-MON-01")
    void everyAuditedActionBecomesOneEntryWithItsActorAndCatalogMetadata(String eventType) throws Exception {
        UUID tenantId = UUID.randomUUID();
        var entry = ActivityEventCatalog.find(eventType).orElseThrow();
        Map<String, Object> payload = samples(tenantId).get(eventType);

        publish(topicOf(eventType), tenantId, eventType, UUID.randomUUID(), payload);

        awaitEntries(tenantId, 1);
        Map<String, Object> row = jdbc.queryForMap("select event_type, actor_user_id, metadata::text as metadata from notif.activity_log where tenant_id = ?", tenantId);
        assertThat(row.get("event_type")).isEqualTo(eventType);
        if (entry.actorField() == null) {
            assertThat(row.get("actor_user_id")).isNull();
        } else {
            assertThat(row.get("actor_user_id").toString()).isEqualTo(payload.get(entry.actorField()).toString());
        }
        Map<?, ?> metadata = objectMapper.readValue((String) row.get("metadata"), Map.class);
        for (String field : entry.metadataFields()) {
            if (payload.get(field) != null) {
                assertThat(metadata.get(field)).as(field).hasToString(payload.get(field).toString());
            }
        }
        assertThat(new java.util.ArrayList<Object>(metadata.keySet())).isSubsetOf(entry.metadataFields().toArray());
    }

    @Test
    @Tag("REQ-MON-01")
    void onlyTheCatalogsFieldsAreCopiedSoAnExtraFieldNeverReachesTheJournal() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>(samples(tenantId).get("INVITATION_CREATED"));
        payload.put("email", "invitee@example.com");
        payload.put("token", "secret-token");
        payload.put("note", "free text");

        publish("org.events", tenantId, "INVITATION_CREATED", UUID.randomUUID(), payload);

        awaitEntries(tenantId, 1);
        String stored = jdbc.queryForObject("select row_to_json(a)::text from notif.activity_log a where tenant_id = ?", String.class, tenantId);
        assertThat(stored).doesNotContain("invitee@example.com", "secret-token", "free text", "email", "token", "note");
    }

    @ParameterizedTest(name = "{0} never reaches a tenant journal")
    @ValueSource(strings = {"PASSWORD_RESET_REQUESTED", "PASSWORD_CHANGED", "SEND_PASSWORD_RESET_EMAIL", "USER_REGISTERED",
            "MEMBER_OFFBOARDED", "SEND_INVITATION_EMAIL", "SOMETHING_CREATED", "login", "", "LEAVE_REQUESTED",
            "LUNCH_GROUPS_FORMED", "LUNCH_ROUND_CANCELLED"})
    @Tag("REQ-PWD-08")
    @Tag("REQ-MON-01")
    void eventsThatAreNotAuditedActionsAreIgnoredEvenWithATenant(String eventType) throws Exception {
        UUID tenantId = UUID.randomUUID();
        publish("auth.events", tenantId, eventType, UUID.randomUUID(), Map.of("userId", ACTOR, "tenantId", tenantId));
        // a later audited event of the same tenant proves the earlier one was read and skipped
        publish("auth.events", tenantId, "LOGIN", UUID.randomUUID(), samples(tenantId).get("LOGIN"));

        awaitEntries(tenantId, 1);
        assertThat(jdbc.queryForList("select event_type from notif.activity_log where tenant_id = ?", String.class, tenantId))
                .containsExactly("LOGIN");
    }

    @Test
    @Tag("REQ-PWD-08")
    void anIdentityEventWithNoTenantIsNeverWrittenAnywhere() throws Exception {
        UUID sentinelTenant = UUID.randomUUID();
        long before = totalEntries();
        publish("auth.events", null, "LOGIN", UUID.randomUUID(), Map.of("userId", ACTOR, "role", "ADMIN"));
        publish("auth.events", sentinelTenant, "LOGIN", UUID.randomUUID(), samples(sentinelTenant).get("LOGIN"));

        awaitEntries(sentinelTenant, 1);
        assertThat(totalEntries()).isEqualTo(before + 1);
    }

    @Test
    @Tag("REQ-EVT-05")
    void aRedeliveredEventIsJournaledOnce() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publish("org.events", tenantId, "THEME_CHANGED", eventId, samples(tenantId).get("THEME_CHANGED"));
        publish("org.events", tenantId, "THEME_CHANGED", eventId, samples(tenantId).get("THEME_CHANGED"));
        publish("org.events", tenantId, "LOGO_CHANGED", UUID.randomUUID(), samples(tenantId).get("LOGO_CHANGED"));

        awaitEntries(tenantId, 2);
        assertThat(jdbc.queryForList("select event_type from notif.activity_log where tenant_id = ? order by event_type", String.class, tenantId))
                .containsExactly("LOGO_CHANGED", "THEME_CHANGED");
    }

    @Test
    @Tag("REQ-DATA-03")
    void eachTenantGetsOnlyItsOwnEntries() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        publish("org.events", tenantA, "THEME_CHANGED", UUID.randomUUID(), samples(tenantA).get("THEME_CHANGED"));
        publish("org.events", tenantB, "LOGO_CHANGED", UUID.randomUUID(), samples(tenantB).get("LOGO_CHANGED"));
        publish("org.events", tenantB, "INVITATION_CREATED", UUID.randomUUID(), samples(tenantB).get("INVITATION_CREATED"));

        awaitEntries(tenantB, 2);
        awaitEntries(tenantA, 1);
        assertThat(jdbc.queryForList("select event_type from notif.activity_log where tenant_id = ?", String.class, tenantA))
                .containsExactly("THEME_CHANGED");
    }

    @Test
    @Tag("REQ-MON-06")
    void anEntryHasTheGenericShapeAndIsStampedWithTheRecordingTime() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(5);

        publish("billing.events", tenantId, "SUBSCRIPTION_CHANGED", UUID.randomUUID(), samples(tenantId).get("SUBSCRIPTION_CHANGED"));

        awaitEntries(tenantId, 1);
        Map<String, Object> row = jdbc.queryForMap("select id, tenant_id, event_type, actor_user_id, metadata, created_at from notif.activity_log where tenant_id = ?", tenantId);
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "tenant_id", "event_type", "actor_user_id", "metadata", "created_at");
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isBetween(before, Instant.now().plusSeconds(5));
    }

    @Test
    @Tag("REQ-MON-01")
    void aSystemInitiatedSubscriptionChangeHasNoActor() throws Exception {
        UUID tenantId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>(samples(tenantId).get("SUBSCRIPTION_CHANGED"));
        payload.put("actorUserId", null);

        publish("billing.events", tenantId, "SUBSCRIPTION_CHANGED", UUID.randomUUID(), payload);

        awaitEntries(tenantId, 1);
        assertThat(jdbc.queryForObject("select actor_user_id from notif.activity_log where tenant_id = ?", UUID.class, tenantId)).isNull();
    }

    private static String topicOf(String eventType) {
        return switch (eventType) {
            case "LOGIN" -> "auth.events";
            case "SUBSCRIPTION_CHANGED" -> "billing.events";
            default -> "org.events";
        };
    }

    private void awaitEntries(UUID tenantId, int expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(entries(tenantId)).isEqualTo(expected));
    }

    private int entries(UUID tenantId) {
        return jdbc.queryForObject("select count(*) from notif.activity_log where tenant_id = ?", Integer.class, tenantId);
    }

    private long totalEntries() {
        return jdbc.queryForObject("select count(*) from notif.activity_log", Long.class);
    }

    /** The envelope in the exact wire format the outbox relay publishes (its constructor is package-private). */
    private void publish(String topic, UUID tenantId, String eventType, UUID eventId, Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", eventType);
        envelope.put("version", 1);
        envelope.put("tenantId", tenantId == null ? null : tenantId.toString());
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, String.valueOf(tenantId), objectMapper.writeValueAsString(envelope))).get();
        }
    }
}
