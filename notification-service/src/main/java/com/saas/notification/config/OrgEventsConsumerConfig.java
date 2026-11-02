package com.saas.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.events.MemberOffboardedListener;
import com.saas.notification.repository.EmailLogRepository;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.shared.events.consumer.KafkaDeadLetterSupport;
import com.saas.shared.events.consumer.ProcessedEventStore;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires {@link MemberOffboardedListener} (REQ-DATA-01, REQ-NOTIF-05) and the dead-letter handling for
 * the Kafka listener container it runs in (REQ-EVT-07). Explicit {@code @Bean} methods, same style as
 * organization-service's {@code SubscriptionProjectionConfig}.
 */
@Configuration
public class OrgEventsConsumerConfig {

    @Bean
    public MemberOffboardedListener memberOffboardedListener(InAppNotificationRepository notificationRepository,
            EmailLogRepository emailLogRepository, DataSource dataSource, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        return new MemberOffboardedListener(notificationRepository, emailLogRepository,
                new ProcessedEventStore(dataSource), objectMapper, transactionManager);
    }

    /** Dedicated producer, used only to publish a failed record to org.events.DLT after 5 attempts (REQ-EVT-07). */
    @Bean
    public KafkaTemplate<String, String> orgEventsDeadLetterTemplate(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> orgEventsDeadLetterTemplate,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(KafkaDeadLetterSupport.errorHandler(orgEventsDeadLetterTemplate, 1000L));
        // a custom factory bypasses Boot's configurer, which is what would otherwise apply this property
        factory.setAutoStartup(autoStartup);
        return factory;
    }
}
