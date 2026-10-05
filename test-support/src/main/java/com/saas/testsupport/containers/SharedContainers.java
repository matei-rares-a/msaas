package com.saas.testsupport.containers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One container per type for the whole JVM, started lazily on first use and never stopped
 * explicitly (Ryuk removes them at JVM exit), so every test class and Spring context reuses them.
 */
public final class SharedContainers {

    public static final String POSTGRES_IMAGE = "postgres:15-alpine";
    public static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.6.1";
    public static final String RABBITMQ_IMAGE = "rabbitmq:3.13-management-alpine";
    public static final String REDIS_IMAGE = "redis:7-alpine";
    /** Not a singleton: the topology test builds its own network around it. */
    public static final String PGBOUNCER_IMAGE = "edoburu/pgbouncer:v1.24.1-p1";

    private SharedContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return PostgresHolder.INSTANCE;
    }

    public static KafkaContainer kafka() {
        return KafkaHolder.INSTANCE;
    }

    public static RabbitMQContainer rabbitMq() {
        return RabbitHolder.INSTANCE;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    private static <T extends GenericContainer<?>> T started(T container) {
        container.start();
        return container;
    }

    private static final class PostgresHolder {
        static final PostgreSQLContainer<?> INSTANCE = started(new PostgreSQLContainer<>(
                DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres")));
    }

    private static final class KafkaHolder {
        static final KafkaContainer INSTANCE = started(new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE)).withKraft());
    }

    private static final class RabbitHolder {
        static final RabbitMQContainer INSTANCE = started(new RabbitMQContainer(
                DockerImageName.parse(RABBITMQ_IMAGE).asCompatibleSubstituteFor("rabbitmq")));
    }

    private static final class RedisHolder {
        static final GenericContainer<?> INSTANCE = started(
                new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379));
    }
}
