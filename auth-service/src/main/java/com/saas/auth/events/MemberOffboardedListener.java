package com.saas.auth.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.auth.service.IdentityErasureService;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to a member being removed from a tenant (REQ-DATA-01): revokes that tenant's refresh tokens
 * and erases the global identity when it was the user's last tenant ({@link IdentityErasureService}).
 * Ids only on the wire (REQ-EVT-02); idempotent for at-least-once delivery (REQ-EVT-05). The
 * idempotency record and the erasure share one transaction, so a failed erasure (e.g. org-service
 * unreachable) rolls the record back and the retry/dead-letter path (REQ-EVT-07) runs it again.
 */
public class MemberOffboardedListener {

    private final IdentityErasureService erasureService;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public MemberOffboardedListener(IdentityErasureService erasureService, ProcessedEventStore processedEventStore,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.erasureService = erasureService;
        this.processedEventStore = processedEventStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = "org.events", groupId = "auth-service")
    public void onOrgEvent(String payload) throws JsonProcessingException {
        EventEnvelope<MemberOffboardedPayload> envelope = objectMapper.readValue(payload,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, MemberOffboardedPayload.class));
        if (!"MEMBER_OFFBOARDED".equals(envelope.getEventType())) {
            return; // org.events carries other event types too (forward-compatible, not an error)
        }

        transactionTemplate.executeWithoutResult(status -> {
            if (!processedEventStore.markProcessed(envelope.getEventId(), envelope.getTenantId())) {
                return;
            }
            erasureService.onMemberOffboarded(envelope.getPayload().userId(), envelope.getPayload().tenantId());
        });
    }
}
