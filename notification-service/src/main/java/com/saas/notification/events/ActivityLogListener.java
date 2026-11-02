package com.saas.notification.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogWriter;
import com.saas.shared.events.ActivityEventCatalog;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.consumer.EventConsumerSupport;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the tenant activity journal from the domain events (REQ-MON-01). It reads the three topics
 * that carry audited actions and records an event only when its type is in
 * {@link ActivityEventCatalog} and it belongs to a tenant: everything else - a password reset, a
 * registration, a type nobody listed - is ignored, so identity events can never reach a tenant's journal
 * (REQ-PWD-08) and the journal stays a list of sensitive actions, not of every change (REQ-MON-01).
 * Only the fields the catalog names are copied into the entry, so a producer adding a field cannot leak it.
 *
 * <p>Has its own consumer group: another listener in this service reads {@code org.events} too (offboarding),
 * and two listeners in one group would split the partitions between them instead of each seeing every event.
 * At-least-once delivery is handled by an idempotency record in the same transaction as the entry
 * (REQ-EVT-05); its id is derived from the event id, so this consumer's records can never collide with
 * another consumer's record of the same event in the shared table.
 */
public class ActivityLogListener {

    static final String CONSUMER_GROUP = "notif-service-activity-log";

    private final ActivityLogWriter writer;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ActivityLogListener(ActivityLogWriter writer, ProcessedEventStore processedEventStore,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.processedEventStore = processedEventStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = {"org.events", "billing.events", "auth.events"}, groupId = CONSUMER_GROUP)
    public void onEvent(String message) throws JsonProcessingException {
        String eventType = objectMapper.readTree(message).path("eventType").asText(null);
        var entry = ActivityEventCatalog.find(eventType);
        if (entry.isEmpty()) {
            return; // not an audited action
        }
        EventEnvelope<ActivityEventPayload> envelope = objectMapper.readValue(message,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, ActivityEventPayload.class));
        if (!envelope.isTenantScoped()) {
            return; // an identity-level event has no tenant journal to go to
        }

        EventConsumerSupport.runWithTenantFromEnvelope(envelope, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!processedEventStore.markProcessed(idempotencyId(envelope.getEventId()), envelope.getTenantId())) {
                return;
            }
            Map<String, Object> fields = envelope.getPayload().fields();
            UUID actor = null;
            if (entry.get().actorField() != null && fields.get(entry.get().actorField()) != null) {
                actor = UUID.fromString(fields.get(entry.get().actorField()).toString());
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (String field : entry.get().metadataFields()) {
                if (fields.get(field) != null) {
                    metadata.put(field, fields.get(field));
                }
            }
            writer.record(envelope.getTenantId(), eventType, actor, metadata);
        }));
    }

    static UUID idempotencyId(UUID eventId) {
        return UUID.nameUUIDFromBytes((CONSUMER_GROUP + ":" + eventId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
