package com.saas.testsupport.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;

/** Implement on a {@code @SpringBootTest} class to get Redis wired to the shared instance. */
public interface RedisTestContainer {

    @ServiceConnection(name = "redis")
    GenericContainer<?> REDIS = SharedContainers.redis();
}
