package com.saas.notification.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.entity.EmailLog;
import com.saas.notification.repository.EmailLogRepository;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.consumer.EventConsumerSupport;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cleans up what notification-service owns for a member removed from a tenant (REQ-DATA-01,
 * REQ-NOTIF-05): the user's in-app notifications in that tenant are deleted and the recipient address
 * of the email logs sent to them is anonymized (the log row stays, so the delivery history is intact).
 * The event carries ids only (REQ-EVT-02); idempotent for at-least-once delivery (REQ-EVT-05).
 */
public class MemberOffboardedListener {

    private static final String ANONYMIZED_EMAIL_DOMAIN = "@anonymized.invalid";

    private final InAppNotificationRepository notificationRepository;
    private final EmailLogRepository emailLogRepository;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public MemberOffboardedListener(InAppNotificationRepository notificationRepository,
            EmailLogRepository emailLogRepository, ProcessedEventStore processedEventStore,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.emailLogRepository = emailLogRepository;
        this.processedEventStore = processedEventStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = "org.events", groupId = "notif-service")
    public void onOrgEvent(String payload) throws JsonProcessingException {
        EventEnvelope<MemberOffboardedPayload> envelope = objectMapper.readValue(payload,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, MemberOffboardedPayload.class));
        if (!"MEMBER_OFFBOARDED".equals(envelope.getEventType())) {
            return; // org.events carries other event types too (forward-compatible, not an error)
        }

        EventConsumerSupport.runWithTenantFromEnvelope(envelope, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!processedEventStore.markProcessed(envelope.getEventId(), envelope.getTenantId())) {
                return;
            }
            var userId = envelope.getPayload().userId();
            notificationRepository.deleteAllByUserId(userId);
            for (EmailLog log : emailLogRepository.findAllByRecipientUserId(userId)) {
                log.setToEmail("removed-" + log.getId() + ANONYMIZED_EMAIL_DOMAIN);
                emailLogRepository.save(log);
            }
        }));
    }
}
