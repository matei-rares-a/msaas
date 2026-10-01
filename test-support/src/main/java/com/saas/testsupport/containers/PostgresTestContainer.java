package com.saas.testsupport.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/** Implement on a {@code @SpringBootTest} class to get a DataSource wired to the shared Postgres. */
public interface PostgresTestContainer {

    @ServiceConnection
    PostgreSQLContainer<?> POSTGRES = SharedContainers.postgres();
}
