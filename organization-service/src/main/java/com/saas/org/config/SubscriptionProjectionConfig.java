package com.saas.org.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.events.SubscriptionChangedListener;
import com.saas.org.repository.TenantRepository;
import com.saas.shared.events.consumer.KafkaDeadLetterSupport;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires {@link SubscriptionChangedListener} (REQ-BILL-03) and the dead-letter handling for the Kafka
 * listener container it runs in (REQ-EVT-07). Explicit {@code @Bean} methods, same style as
 * {@code AdminDemotionEscalationConfig}, not {@code @Component} auto-detection, for control on the
 * constructor.
 */
@Configuration
public class SubscriptionProjectionConfig {

    @Bean
    public SubscriptionChangedListener subscriptionChangedListener(TenantRepository tenantRepository,
            DataSource dataSource, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        return new SubscriptionChangedListener(tenantRepository, new ProcessedEventStore(dataSource), objectMapper, transactionManager);
    }

    /** Dedicated producer, independent of saas.outbox.relay.enabled (that one is for the relay only):
     * needed here only to publish a failed record to billing.events.DLT after 5 attempts (REQ-EVT-07). */
    @Bean
    public KafkaTemplate<String, String> subscriptionEventsDeadLetterTemplate(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> subscriptionEventsDeadLetterTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(KafkaDeadLetterSupport.errorHandler(subscriptionEventsDeadLetterTemplate, 1000L));
        return factory;
    }
}
