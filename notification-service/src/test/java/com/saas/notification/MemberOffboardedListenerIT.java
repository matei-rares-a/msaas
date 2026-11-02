package com.saas.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.testsupport.containers.KafkaTestContainer;
import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.containers.SharedContainers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Offboarding a member removes what notification-service holds for them in that tenant only
 * (REQ-DATA-01, REQ-NOTIF-05), driven by the real {@code MEMBER_OFFBOARDED} event over Kafka. The
 * listener stays enabled here (the one IT in this service that needs it running).
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // the shared test Postgres is used as a superuser, so the role's search_path (set by the real
        // topology) is missing; ProcessedEventStore uses an unqualified table name
        "spring.datasource.hikari.connection-init-sql=SET search_path TO notif,public",
        // a brand-new consumer group would otherwise start after whatever the topic already holds
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class MemberOffboardedListenerIT implements PostgresTestContainer, KafkaTestContainer {

    static {
        // A listener that subscribes to a topic that does not exist yet only notices it after the
        // consumer's metadata refresh (minutes); creating it first keeps the test deterministic.
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("org.events", 1, (short) 1))).all().get();
        } catch (Exception e) {
            if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Tag("REQ-NOTIF-05")
    @Tag("REQ-DATA-01")
    void offboardingDeletesTheUsersNotificationsInThatTenantOnly() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        insertNotification(tenantA, leaving);
        insertNotification(tenantA, leaving);
        insertNotification(tenantA, staying);
        insertNotification(tenantB, leaving);

        publish(tenantA, leaving, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(countNotifications(tenantA, leaving)).isZero());
        assertThat(countNotifications(tenantA, staying)).isEqualTo(1);
        assertThat(countNotifications(tenantB, leaving)).isEqualTo(1);
    }

    @Test
    @Tag("REQ-DATA-01")
    void offboardingAnonymizesTheEmailLogRecipientButKeepsTheRow() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        UUID logOfLeaving = insertEmailLog(tenant, "former.member@example.com", leaving);
        UUID logOfInvitee = insertEmailLog(tenant, "someone.invited@example.com", null);

        publish(tenant, leaving, UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(toEmail(logOfLeaving)).doesNotContain("former.member@example.com").endsWith("@anonymized.invalid"));
        assertThat(jdbc.queryForObject("select count(*) from notif.email_logs where id = ?", Integer.class, logOfLeaving)).isEqualTo(1);
        assertThat(toEmail(logOfInvitee)).isEqualTo("someone.invited@example.com");
    }

    @Test
    @Tag("REQ-EVT-05")
    void aRedeliveredEventIsProcessedOnlyOnce() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertNotification(tenant, leaving);
        insertNotification(tenant, marker);

        publish(tenant, leaving, eventId);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countNotifications(tenant, leaving)).isZero());

        // same eventId again, after a fresh notification: if it were processed twice this one would vanish
        insertNotification(tenant, leaving);
        publish(tenant, leaving, eventId);
        // events of one tenant share a partition, so once this later event is handled the redelivery was too
        publish(tenant, marker, UUID.randomUUID());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countNotifications(tenant, marker)).isZero());

        assertThat(countNotifications(tenant, leaving)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notif.processed_events where id = ?", Integer.class, eventId)).isEqualTo(1);
    }

    private void publish(UUID tenantId, UUID userId, UUID eventId) throws Exception {
        // the envelope's constructor is package-private; the wire format is what the listener consumes anyway
        String json = objectMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", "MEMBER_OFFBOARDED",
                "version", 1,
                "tenantId", tenantId.toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", Map.of("tenantId", tenantId.toString(), "userId", userId.toString())));
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("org.events", tenantId.toString(), json)).get();
        }
    }

    private void insertNotification(UUID tenant, UUID user) {
        jdbc.update("insert into notif.in_app_notifications (id, tenant_id, user_id, title, message, level) values (?, ?, ?, 't', 'm', 'INFO')",
                UUID.randomUUID(), tenant, user);
    }

    private UUID insertEmailLog(UUID tenant, String email, UUID recipientUser) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into notif.email_logs (id, tenant_id, to_email, status, recipient_user_id) values (?, ?, ?, 'SENT', ?)",
                id, tenant, email, recipientUser);
        return id;
    }

    private int countNotifications(UUID tenant, UUID user) {
        return jdbc.queryForObject("select count(*) from notif.in_app_notifications where tenant_id = ? and user_id = ?",
                Integer.class, tenant, user);
    }

    private String toEmail(UUID logId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select to_email from notif.email_logs where id = ?", logId);
        return (String) rows.get(0).get("to_email");
    }
}
