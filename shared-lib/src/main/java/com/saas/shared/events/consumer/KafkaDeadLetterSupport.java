package com.saas.shared.events.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 5 attempts with a fixed backoff, then the record is published as-is to {@code <topic>.DLT}
 * (Kafka's conventional dead-letter topic suffix) and logged (REQ-EVT-07).
 */
public final class KafkaDeadLetterSupport {

    private KafkaDeadLetterSupport() {
    }

    public static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> deadLetterTemplate, long backOffMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterTemplate);
        // DefaultErrorHandler retries in addition to the first attempt: 4 retries + 1 = 5 total attempts
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(backOffMs, 4L));
        handler.setRetryListeners((ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) ->
                org.slf4j.LoggerFactory.getLogger(KafkaDeadLetterSupport.class).warn(
                        "Retrying Kafka record (topic={}, attempt={}): {}", record.topic(), deliveryAttempt, ex.toString()));
        return handler;
    }
}
