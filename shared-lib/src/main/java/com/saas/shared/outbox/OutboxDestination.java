package com.saas.shared.outbox;

/** Where the relay publishes an outbox row (REQ-EVT-01): Kafka for domain events, RabbitMQ for commands/jobs. */
public enum OutboxDestination {
    KAFKA,
    RABBITMQ
}
