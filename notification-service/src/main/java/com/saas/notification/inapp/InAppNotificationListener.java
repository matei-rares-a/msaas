package com.saas.notification.inapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.shared.events.consumer.ProcessedEventStore;
import com.saas.shared.tenancy.TenantContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Creates the in-app notifications from the domain events (REQ-NOTIF-03): reads the topics of the services that
 * publish them and, for the event types {@link InAppNotificationRules} knows, creates the notifications that
 * event calls for, all in one transaction of the event's own tenant. Everything else is ignored, including an
 * event with no tenant - an identity-level event (a password change) is told to the user by email only
 * (REQ-NOTIF-01).
 *
 * <p>Own consumer group, for the reason given on {@code ActivityLogListener}: another listener in a shared group
 * would split the partitions instead of each seeing every event. At-least-once delivery is handled by an
 * idempotency record in the same transaction as the notifications (REQ-EVT-05); its id is derived from the event
 * id and this consumer's group, so it cannot collide with another consumer's record of the same event.
 */
public class InAppNotificationListener {

    static final String CONSUMER_GROUP = "notif-service-in-app";

    private final InAppNotificationRules rules;
    private final InAppNotificationService service;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public InAppNotificationListener(InAppNotificationRules rules, InAppNotificationService service,
            ProcessedEventStore processedEventStore, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.service = service;
        this.processedEventStore = processedEventStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = {"org.events", "billing.events"}, groupId = CONSUMER_GROUP)
    public void onEvent(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        String eventType = envelope.path("eventType").asText(null);
        if (eventType == null || !InAppNotificationRules.HANDLED_EVENT_TYPES.contains(eventType)) {
            return;
        }
        if (!envelope.hasNonNull("tenantId")) {
            return;
        }
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        UUID tenantId = UUID.fromString(envelope.get("tenantId").asText());

        // decided before the transaction, so no database transaction is held open while another service is asked
        List<InAppNotificationService.NewNotification> notifications = rules.notificationsFor(eventType, tenantId, envelope.path("payload"));

        TenantContext.runWith(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!processedEventStore.markProcessed(idempotencyId(eventId), tenantId)) {
                return;
            }
            notifications.forEach(service::create);
        }));
    }

    static UUID idempotencyId(UUID eventId) {
        return UUID.nameUUIDFromBytes((CONSUMER_GROUP + ":" + eventId).getBytes(StandardCharsets.UTF_8));
    }
}
