package com.saas.org.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.cache.CacheRegions;
import com.saas.org.cache.TenantCache;
import com.saas.org.entity.Tenant;
import com.saas.org.event.SubscriptionChangedPayload;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.events.EventEnvelope;
import com.saas.shared.events.consumer.EventConsumerSupport;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First production Kafka consumer in the project: keeps a local, eventually-consistent copy of the
 * plan's maxUsers for invite-time enforcement (REQ-BILL-03, REQ-EVT-09 - projections instead of a
 * synchronous call that would couple invitation availability to billing-service's uptime).
 * Deliberately in its own package (com.saas.org.events) and depends on nothing but
 * {@link TenantRepository}/{@link Tenant} from this service's own model - see
 * NoAutomaticTenantCreationArchTest (REQ-EVT-08): it may only UPDATE a tenant row that already
 * exists, never create one.
 */
public class SubscriptionChangedListener {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionChangedListener.class);

    private final TenantRepository tenantRepository;
    private final ProcessedEventStore processedEventStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TenantCache cache;

    public SubscriptionChangedListener(TenantRepository tenantRepository, ProcessedEventStore processedEventStore,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager, TenantCache cache) {
        this.tenantRepository = tenantRepository;
        this.processedEventStore = processedEventStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cache = cache;
    }

    @KafkaListener(topics = "billing.events", groupId = "org-service")
    public void onBillingEvent(String payload) throws JsonProcessingException {
        EventEnvelope<SubscriptionChangedPayload> envelope = objectMapper.readValue(payload,
                objectMapper.getTypeFactory().constructParametricType(EventEnvelope.class, SubscriptionChangedPayload.class));
        if (!"SUBSCRIPTION_CHANGED".equals(envelope.getEventType())) {
            return; // billing.events may carry other event types later (forward-compatible, not an error)
        }

        // the cached write-access answer is stale the moment billing says the subscription changed (REQ-SCALE-01);
        // removing it is harmless when the event is a duplicate or the tenant is unknown, so it comes first
        cache.evict(CacheRegions.ACCESS_STATUS, envelope.getTenantId(), null);

        EventConsumerSupport.runWithTenantFromEnvelope(envelope, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!processedEventStore.markProcessed(envelope.getEventId(), envelope.getTenantId())) {
                return; // already processed (REQ-EVT-05, at-least-once delivery)
            }
            Tenant tenant = tenantRepository.findById(envelope.getTenantId()).orElse(null);
            if (tenant == null) {
                // No automatic tenant creation (REQ-EVT-08): an event for a tenant this service has
                // never heard of is logged and dropped, not used to create one. Rolls back the
                // markProcessed insert too (whole TransactionTemplate block), but retrying would not
                // help - tenant ids are random UUIDs, never created after the fact with the same id -
                // so there is no point letting Kafka redeliver this into a DLQ either; it is simply
                // not retried (no exception thrown).
                log.warn("SUBSCRIPTION_CHANGED for unknown tenant {}, ignoring", envelope.getTenantId());
                status.setRollbackOnly();
                return;
            }
            tenant.setMaxUsers(envelope.getPayload().maxUsers());
            tenantRepository.save(tenant);
        }));
    }
}
