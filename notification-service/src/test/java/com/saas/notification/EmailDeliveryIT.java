package com.saas.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogPartitionMaintenance;
import com.saas.notification.email.EmailDeliveryException;
import com.saas.notification.email.EmailProvider;
import com.saas.notification.email.EmailSender;
import com.saas.testsupport.containers.PostgresTestContainer;
import com.saas.testsupport.containers.RabbitMqTestContainer;
import com.saas.testsupport.events.EventAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Sending the emails the other services ask for, through the real RabbitMQ queue and consumer (REQ-NOTIF-06..09,
 * REQ-MON-01, REQ-PWD-08). Commands are published the way an outbox relay publishes them - to the producer's
 * {@code <service>.commands} exchange, as the JSON of an envelope - and the field names are written out here,
 * not taken from this service's classes, so a drift from the producers' contract shows up. The provider and
 * the pause between retries are replaced by recorders, so a failing provider and the backoff can be driven and
 * measured without waiting. "Nothing happened" is proved by publishing a further command after the one in
 * question: one consumer handles commands in order, so once that one is done the earlier one has been handled.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "saas.email.listener-retry-backoff-ms=20",
        // the export queue is shared with every other context of the run (see ActivityLogExportIT)
        "saas.export.listener-auto-startup=false",
        "saas.email.app-base-url=https://app.example.com"
})
class EmailDeliveryIT implements PostgresTestContainer, RabbitMqTestContainer {

    private static final String ORG_COMMANDS = "org.commands";
    private static final String AUTH_COMMANDS = "auth.commands";
    private static final String DEAD_LETTER_QUEUE = "notif.email-commands.dlq";

    record Sent(String to, String subject, String body) {
    }

    /** Records what reaches the provider and fails on demand, per recipient. */
    static class RecordingProvider implements EmailProvider {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final List<String> attempted = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> failuresLeft = new ConcurrentHashMap<>();

        void failFor(String address, int times) {
            failuresLeft.put(address, times);
        }

        @Override
        public void send(String to, String subject, String body) throws EmailDeliveryException {
            attempted.add(to);
            int left = failuresLeft.getOrDefault(to, 0);
            if (left > 0) {
                failuresLeft.put(to, left - 1);
                throw new EmailDeliveryException("SMTP_DOWN");
            }
            sent.add(new Sent(to, subject, body));
        }

        long attempts(String address) {
            return attempted.stream().filter(address::equals).count();
        }

        List<Sent> sentTo(String address) {
            return sent.stream().filter(s -> s.to().equals(address)).toList();
        }
    }

    static class RecordingSleeper implements EmailSender.Sleeper {
        final List<Duration> pauses = new CopyOnWriteArrayList<>();

        @Override
        public void sleep(Duration duration) {
            pauses.add(duration);
        }
    }

    @TestConfiguration
    static class Providers {
        @Bean
        @Primary
        RecordingProvider recordingProvider() {
            return new RecordingProvider();
        }

        @Bean
        @Primary
        RecordingSleeper recordingSleeper() {
            return new RecordingSleeper();
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingProvider provider;
    @Autowired
    private RecordingSleeper sleeper;
    @Autowired
    private ActivityLogPartitionMaintenance partitions;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private UUID tenantId;

    @BeforeEach
    void freshTenant() {
        partitions.ensureUpcomingPartitions();
        tenantId = UUID.randomUUID();
    }

    // ---- an invitation email (REQ-NOTIF-06, REQ-NOTIF-07, REQ-MON-01) ----

    @Test
    @Tag("REQ-NOTIF-07")
    @Tag("REQ-MON-01")
    @Tag("REQ-NOTIF-06")
    void anInvitationIsEmailedAndLeavesOneMetadataRowAndOneJournalEntryInItsTenant() throws Exception {
        String address = address("invitee");
        String token = "invite-token-" + UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();

        UUID commandId = invite(ORG_COMMANDS, tenantId, address, token, invitationId);
        awaitLog(commandId);

        List<Sent> received = provider.sentTo(address);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).subject()).isEqualTo("You have been invited to join an organization");
        assertThat(received.get(0).body()).contains("https://app.example.com/accept-invitation?token=" + token).contains("as MANAGER");

        Map<String, Object> row = jdbc.queryForMap("select * from notif.email_logs where id = ?", commandId);
        assertThat(row).containsEntry("tenant_id", tenantId).containsEntry("to_email", address).containsEntry("status", "SENT")
                .containsEntry("event_type", "INVITATION").containsEntry("subject", "You have been invited to join an organization")
                .containsEntry("recipient_user_id", null);
        assertThat(row).doesNotContainKey("body");
        assertThat(jdbc.queryForObject("select row_to_json(e)::text from notif.email_logs e where id = ?", String.class, commandId))
                .doesNotContain(token);

        List<Map<String, Object>> entries = journal(tenantId, "EMAIL_SENT");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0)).containsEntry("actor_user_id", null);
        JsonNode metadata = objectMapper.readTree(entries.get(0).get("metadata").toString());
        assertThat(metadata.get("emailLogId").asText()).isEqualTo(commandId.toString());
        assertThat(metadata.get("template").asText()).isEqualTo("INVITATION");
        assertThat(metadata.get("status").asText()).isEqualTo("SENT");
        assertThat(metadata.get("attempts").asInt()).isEqualTo(1);
        assertThat(metadata.get("invitationId").asText()).isEqualTo(invitationId.toString());
        assertThat(metadata.has("reason")).isFalse();
        assertThat(entries.get(0).get("metadata").toString()).doesNotContain(address).doesNotContain(token);
        assertThat(journal(tenantId, "EMAIL_FAILED")).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-07")
    @Tag("REQ-DATA-03")
    void eachTenantsEmailsAndJournalEntriesStayInThatTenant() throws Exception {
        UUID other = UUID.randomUUID();
        String mine = address("mine");
        String theirs = address("theirs");

        UUID first = invite(ORG_COMMANDS, tenantId, mine, "t1", UUID.randomUUID());
        UUID second = invite(ORG_COMMANDS, other, theirs, "t2", UUID.randomUUID());
        awaitLog(first);
        awaitLog(second);

        assertThat(jdbc.queryForObject("select tenant_id from notif.email_logs where id = ?", UUID.class, first)).isEqualTo(tenantId);
        assertThat(jdbc.queryForObject("select tenant_id from notif.email_logs where id = ?", UUID.class, second)).isEqualTo(other);
        assertThat(journal(tenantId, "EMAIL_SENT")).hasSize(1);
        assertThat(journal(other, "EMAIL_SENT")).hasSize(1);
        assertThat(journal(tenantId, "EMAIL_SENT").toString()).doesNotContain(theirs);
    }

    // ---- retries and FAILED (REQ-NOTIF-08) ----

    @Test
    @Tag("REQ-NOTIF-08")
    @Tag("REQ-NOTIF-07")
    @Tag("REQ-MON-01")
    void aProviderThatKeepsFailingIsRetriedThreeTimesWithDoublingWaitsThenTheEmailIsFailedAndJournaled() throws Exception {
        String address = address("unreachable");
        provider.failFor(address, Integer.MAX_VALUE);
        sleeper.pauses.clear();

        UUID commandId = invite(ORG_COMMANDS, tenantId, address, "t", UUID.randomUUID());
        awaitLog(commandId);
        finishedAfterWith(UUID.randomUUID());

        assertThat(provider.attempts(address)).isEqualTo(4);
        assertThat(provider.sentTo(address)).isEmpty();
        assertThat(sleeper.pauses).startsWith(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(jdbc.queryForObject("select status from notif.email_logs where id = ?", String.class, commandId)).isEqualTo("FAILED");
        List<Map<String, Object>> entries = journal(tenantId, "EMAIL_FAILED");
        assertThat(entries).hasSize(1);
        JsonNode metadata = objectMapper.readTree(entries.get(0).get("metadata").toString());
        assertThat(metadata.get("attempts").asInt()).isEqualTo(4);
        assertThat(metadata.get("reason").asText()).isEqualTo("SMTP_DOWN");
        assertThat(metadata.get("status").asText()).isEqualTo("FAILED");
        assertThat(journal(tenantId, "EMAIL_SENT")).isEmpty();
    }

    @Test
    @Tag("REQ-NOTIF-08")
    void aProviderThatRecoversWithinTheThreeRetriesDeliversAndTheEmailIsSent() throws Exception {
        String address = address("flaky");
        provider.failFor(address, 3);

        UUID commandId = invite(ORG_COMMANDS, tenantId, address, "t", UUID.randomUUID());
        awaitLog(commandId);

        assertThat(provider.attempts(address)).isEqualTo(4);
        assertThat(provider.sentTo(address)).hasSize(1);
        assertThat(jdbc.queryForObject("select status from notif.email_logs where id = ?", String.class, commandId)).isEqualTo("SENT");
        JsonNode metadata = objectMapper.readTree(journal(tenantId, "EMAIL_SENT").get(0).get("metadata").toString());
        assertThat(metadata.get("attempts").asInt()).isEqualTo(4);
    }

    @Test
    @Tag("REQ-NOTIF-08")
    void anAddressThatCouldInjectAHeaderIsNeverHandedToTheProviderAndIsStoredWithoutLineBreaks() throws Exception {
        String malicious = "victim@example.com\r\nBcc: attacker@example.com";

        UUID commandId = invite(ORG_COMMANDS, tenantId, malicious, "t", UUID.randomUUID());
        awaitLog(commandId);

        assertThat(provider.attempted).noneMatch(a -> a.contains("attacker"));
        assertThat(jdbc.queryForObject("select status from notif.email_logs where id = ?", String.class, commandId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select to_email from notif.email_logs where id = ?", String.class, commandId))
                .doesNotContain("\r").doesNotContain("\n");
        JsonNode metadata = objectMapper.readTree(journal(tenantId, "EMAIL_FAILED").get(0).get("metadata").toString());
        assertThat(metadata.get("reason").asText()).isEqualTo("INVALID_ADDRESS");
        assertThat(metadata.get("attempts").asInt()).isZero();
    }

    // ---- members as recipients (REQ-DATA-01) ----

    @Test
    @Tag("REQ-NOTIF-07")
    @Tag("REQ-DATA-01")
    void anEmailToAMemberRecordsWhichMemberSoOffboardingCanAnonymizeTheAddress() throws Exception {
        String address = address("member");
        UUID member = UUID.randomUUID();

        UUID commandId = publish(ORG_COMMANDS, "SEND_TENANT_EMAIL", tenantId, Map.of("tenantId", tenantId, "recipientUserId", member,
                "email", address, "template", "BILLING_NOTICE", "subject", "Your subscription changed", "body", "Hello"));
        awaitLog(commandId);

        assertThat(provider.sentTo(address)).hasSize(1);
        assertThat(jdbc.queryForObject("select recipient_user_id from notif.email_logs where id = ?", UUID.class, commandId)).isEqualTo(member);
        assertThat(jdbc.queryForObject("select event_type from notif.email_logs where id = ?", String.class, commandId)).isEqualTo("BILLING_NOTICE");
        JsonNode metadata = objectMapper.readTree(journal(tenantId, "EMAIL_SENT").get(0).get("metadata").toString());
        assertThat(metadata.get("recipientUserId").asText()).isEqualTo(member.toString());
        assertThat(metadata.get("template").asText()).isEqualTo("BILLING_NOTICE");
        assertThat(metadata.toString()).doesNotContain(address);
    }

    // ---- identity emails are never recorded (REQ-PWD-08) ----

    @Test
    @Tag("REQ-PWD-08")
    @Tag("REQ-NOTIF-07")
    void aPasswordResetIsEmailedButNothingAboutItIsRecordedInAnyTenant() throws Exception {
        String address = address("forgetful");
        String token = "reset-token-" + UUID.randomUUID();
        long logsBefore = count("select count(*) from notif.email_logs");
        long journalBefore = count("select count(*) from notif.activity_log");

        publish(AUTH_COMMANDS, "SEND_PASSWORD_RESET_EMAIL", null, Map.of("userId", UUID.randomUUID(), "email", address, "resetToken", token));
        finishedAfterWith(tenantId);

        List<Sent> received = provider.sentTo(address);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).subject()).isEqualTo("Reset your password");
        assertThat(received.get(0).body()).contains("https://app.example.com/reset-password?token=" + token);
        assertThat(count("select count(*) from notif.email_logs")).as("only the follow-up command's own row").isEqualTo(logsBefore + 1);
        assertThat(count("select count(*) from notif.activity_log")).isEqualTo(journalBefore + 1);
        assertThat(count("select count(*) from notif.email_logs where to_email = ?", address)).isZero();
        assertNothingMentions(address, token);
    }

    @Test
    @Tag("REQ-PWD-08")
    @Tag("REQ-NOTIF-07")
    void aPasswordChangeConfirmationIsEmailedWithContactHintsAndNothingIsRecorded() throws Exception {
        String address = address("changed");
        long logsBefore = count("select count(*) from notif.email_logs");
        long journalBefore = count("select count(*) from notif.activity_log");

        publish(AUTH_COMMANDS, "SEND_PASSWORD_CHANGED_CONFIRMATION", null, Map.of("userId", UUID.randomUUID(), "email", address));
        finishedAfterWith(tenantId);

        List<Sent> received = provider.sentTo(address);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).subject()).isEqualTo("Your password was changed");
        assertThat(received.get(0).body()).contains("support@example.com");
        assertThat(count("select count(*) from notif.email_logs")).isEqualTo(logsBefore + 1);
        assertThat(count("select count(*) from notif.activity_log")).isEqualTo(journalBefore + 1);
        assertNothingMentions(address);
    }

    @Test
    @Tag("REQ-PWD-08")
    @Tag("REQ-NOTIF-08")
    void anIdentityEmailThatCannotBeDeliveredIsRetriedAndStillLeavesNoRecord() throws Exception {
        String address = address("bounced");
        provider.failFor(address, Integer.MAX_VALUE);
        long logsBefore = count("select count(*) from notif.email_logs");
        long journalBefore = count("select count(*) from notif.activity_log");

        publish(AUTH_COMMANDS, "SEND_PASSWORD_RESET_EMAIL", null, Map.of("userId", UUID.randomUUID(), "email", address, "resetToken", "t"));
        finishedAfterWith(tenantId);

        assertThat(provider.attempts(address)).isEqualTo(4);
        assertThat(count("select count(*) from notif.email_logs")).isEqualTo(logsBefore + 1);
        assertThat(count("select count(*) from notif.activity_log")).isEqualTo(journalBefore + 1);
        assertNothingMentions(address);
    }

    @Test
    @Tag("REQ-PWD-08")
    void aPasswordCommandThatNamesATenantIsStillTreatedAsIdentityLevelAndNotRecordedInIt() throws Exception {
        String address = address("sneaky");
        long journalBefore = journal(tenantId);

        publish(AUTH_COMMANDS, "SEND_PASSWORD_RESET_EMAIL", tenantId, Map.of("userId", UUID.randomUUID(), "email", address, "resetToken", "t"));
        finishedAfterWith(tenantId);

        assertThat(provider.sentTo(address)).hasSize(1);
        assertThat(journal(tenantId)).as("only the follow-up's entry").isEqualTo(journalBefore + 1);
        assertThat(count("select count(*) from notif.email_logs where to_email = ?", address)).isZero();
    }

    // ---- delivery guarantees (REQ-EVT-05, REQ-EVT-07) ----

    @Test
    @Tag("REQ-EVT-05")
    @Tag("REQ-NOTIF-07")
    void aTenantCommandDeliveredTwiceSendsOneEmailAndRecordsOnce() throws Exception {
        String address = address("twice");
        UUID commandId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("invitationId", UUID.randomUUID(), "tenantId", tenantId, "email", address, "role", "USER", "token", "t");

        publish(ORG_COMMANDS, "SEND_INVITATION_EMAIL", tenantId, payload, commandId);
        publish(ORG_COMMANDS, "SEND_INVITATION_EMAIL", tenantId, payload, commandId);
        finishedAfterWith(tenantId);

        assertThat(provider.sentTo(address)).hasSize(1);
        assertThat(count("select count(*) from notif.email_logs where id = ?", commandId)).isEqualTo(1);
        assertThat(journal(tenantId, "EMAIL_SENT").stream().filter(e -> e.get("metadata").toString().contains(commandId.toString()))).hasSize(1);
    }

    @Test
    @Tag("REQ-EVT-07")
    void aCommandThatCannotBeReadEndsInTheDeadLetterQueueAndTheNextOneIsStillHandled() throws Exception {
        String marker = "garbled-" + UUID.randomUUID();
        rabbitTemplate.convertAndSend(ORG_COMMANDS, "", "{not json " + marker);
        String address = address("after-garbage");

        UUID commandId = invite(ORG_COMMANDS, tenantId, address, "t", UUID.randomUUID());
        awaitLog(commandId);

        assertThat(EventAssertions.awaitRabbitMessage(DEAD_LETTER_QUEUE, message -> message.contains(marker))).contains(marker);
    }

    @Test
    @Tag("REQ-EVT-07")
    void aTenantCommandThatNamesNoTenantIsDeadLetteredAndNotSent() throws Exception {
        String address = address("homeless");
        UUID commandId = publish(ORG_COMMANDS, "SEND_INVITATION_EMAIL", null,
                Map.of("invitationId", UUID.randomUUID(), "email", address, "role", "USER", "token", "t"));

        assertThat(EventAssertions.awaitRabbitMessage(DEAD_LETTER_QUEUE, message -> message.contains(commandId.toString()))).contains(commandId.toString());
        assertThat(provider.attempts(address)).isZero();
    }

    @Test
    @Tag("REQ-EVT-01")
    void aCommandOfAnotherTypeOnTheSameExchangeIsIgnoredWithoutError() throws Exception {
        String address = address("other");

        publish(ORG_COMMANDS, "SOMETHING_ELSE", tenantId, Map.of("email", address));
        finishedAfterWith(tenantId);

        assertThat(provider.attempts(address)).isZero();
        assertThat(count("select count(*) from notif.email_logs where to_email = ?", address)).isZero();
    }

    // ---- users cannot switch emails off (REQ-NOTIF-09) ----

    @Test
    @Tag("REQ-NOTIF-09")
    void thereIsNoEndpointOrTableWhereAUserCouldTurnEmailsOrNotificationsOff() {
        var paths = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream()).toList();
        List<String> tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'notif'", String.class);
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_schema = 'notif'", String.class);

        String optOut = "(?i).*(preference|setting|opt-?out|unsubscribe|mute|disable|silence|subscription).*";
        assertThat(paths).isNotEmpty().noneMatch(p -> p.matches(optOut));
        assertThat(tables).isNotEmpty().noneMatch(t -> t.matches(optOut));
        assertThat(columns).noneMatch(c -> c.matches(optOut));
    }

    // ---- helpers ----

    private static String address(String who) {
        return who + "-" + UUID.randomUUID() + "@example.com";
    }

    private UUID invite(String exchange, UUID tenant, String address, String token, UUID invitationId) throws Exception {
        return publish(exchange, "SEND_INVITATION_EMAIL", tenant,
                Map.of("invitationId", invitationId, "tenantId", tenant, "email", address, "role", "MANAGER", "token", token));
    }

    private UUID publish(String exchange, String type, UUID tenant, Map<String, Object> payload) throws Exception {
        return publish(exchange, type, tenant, payload, UUID.randomUUID());
    }

    private UUID publish(String exchange, String type, UUID tenant, Map<String, Object> payload, UUID eventId) throws Exception {
        java.util.LinkedHashMap<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type);
        envelope.put("version", 1);
        if (tenant != null) {
            envelope.put("tenantId", tenant);
        }
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", payload);
        rabbitTemplate.convertAndSend(exchange, tenant == null ? "identity" : tenant.toString(), objectMapper.writeValueAsString(envelope));
        return eventId;
    }

    private void awaitLog(UUID commandId) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("select count(*) from notif.email_logs where id = ?", commandId)).isEqualTo(1));
    }

    /** A later command in the same queue: once it is recorded, everything published before it has been handled. */
    private void finishedAfterWith(UUID tenant) throws Exception {
        UUID followUp = invite(ORG_COMMANDS, tenant, address("follow-up"), "t", UUID.randomUUID());
        awaitLog(followUp);
    }

    private void assertNothingMentions(String... needles) {
        for (String needle : needles) {
            assertThat(count("select count(*) from notif.activity_log where metadata::text like ?", "%" + needle + "%")).as(needle).isZero();
            assertThat(count("select count(*) from notif.email_logs e where row_to_json(e)::text like ?", "%" + needle + "%")).as(needle).isZero();
        }
    }

    private List<Map<String, Object>> journal(UUID tenant, String type) {
        return jdbc.queryForList("select actor_user_id, metadata::text as metadata from notif.activity_log"
                + " where tenant_id = ? and event_type = ? order by created_at", tenant, type);
    }

    private long journal(UUID tenant) {
        return count("select count(*) from notif.activity_log where tenant_id = ?", tenant);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
