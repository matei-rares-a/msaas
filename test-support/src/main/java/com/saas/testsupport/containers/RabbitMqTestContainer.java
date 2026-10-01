package com.saas.testsupport.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;

/** Implement on a {@code @SpringBootTest} class to get RabbitMQ wired to the shared broker. */
public interface RabbitMqTestContainer {

    @ServiceConnection
    RabbitMQContainer RABBITMQ = SharedContainers.rabbitMq();
}
