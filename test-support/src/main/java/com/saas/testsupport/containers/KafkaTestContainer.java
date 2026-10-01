package com.saas.testsupport.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.KafkaContainer;

/** Implement on a {@code @SpringBootTest} class to get Kafka wired to the shared broker. */
public interface KafkaTestContainer {

    @ServiceConnection
    KafkaContainer KAFKA = SharedContainers.kafka();
}
