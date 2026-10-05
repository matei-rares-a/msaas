package com.saas.shared.events.consumer;

import com.saas.testsupport.containers.KafkaTestContainer;
import com.saas.testsupport.events.EventAssertions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener that always throws is retried 5 times with backoff, then the record lands on the Kafka
 * dead-letter topic ({@code <topic>.DLT}) and the failures are logged (REQ-EVT-07).
 */
@SpringBootTest(classes = KafkaDeadLetterIT.BrokerApp.class,
        properties = {"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "spring.kafka.consumer.auto-offset-reset=earliest"})
class KafkaDeadLetterIT implements KafkaTestContainer {

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaMessageListenerContainer<String, String> container;

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Tag("REQ-EVT-07")
    void aListenerThatAlwaysFailsSendsTheRecordToTheDeadLetterTopicAfterFiveAttempts() {
        String topic = "dlq-it-" + UUID.randomUUID();
        String value = UUID.randomUUID().toString();
        AtomicInteger attempts = new AtomicInteger();

        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setGroupId("dlq-it-" + UUID.randomUUID());
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        });
        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setCommonErrorHandler(KafkaDeadLetterSupport.errorHandler(kafkaTemplate, 50L));
        container.start();

        kafkaTemplate.send(topic, "key", value);

        ConsumerRecord<String, String> onDeadLetterTopic = EventAssertions.awaitKafkaRecord(
                topic + ".DLT", r -> value.equals(r.value()));
        assertThat(onDeadLetterTopic.value()).isEqualTo(value);
        assertThat(attempts).hasValue(5);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
    static class BrokerApp {
    }
}
