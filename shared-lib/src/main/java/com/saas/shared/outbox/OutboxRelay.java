package com.saas.shared.outbox;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls one service's {@code outbox} table and publishes pending rows to the right broker (REQ-EVT-01,
 * REQ-EVT-04). Connects with the dedicated {@code outbox_relay} role, which bypasses RLS, so it can see
 * every tenant's pending rows without an app.tenant_id in scope. Selecting (with {@code FOR UPDATE SKIP
 * LOCKED}) and marking a row published are each their own short DB transaction; the broker call in
 * between runs with no DB transaction open, so a slow or failed publish never holds a row lock. A row is
 * only marked published after a successful send, so a crash before that redelivers it on the next poll
 * (at-least-once, REQ-EVT-05 - consumers are made idempotent in P3.3).
 */
public class OutboxRelay {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String schema;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(DataSource relayDataSource, String schema,
                        KafkaTemplate<String, String> kafkaTemplate, RabbitTemplate rabbitTemplate) {
        this.jdbcTemplate = new JdbcTemplate(relayDataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(relayDataSource));
        this.schema = schema;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    /** @return how many rows were published in this batch. */
    public int pollAndPublishOnce(int batchSize) {
        List<OutboxRow> rows = transactionTemplate.execute(status -> jdbcTemplate.query(
                "SELECT id, destination, channel, routing_key, payload FROM " + schema + ".outbox"
                        + " WHERE published_at IS NULL ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new OutboxRow(
                        UUID.fromString(rs.getString("id")),
                        OutboxDestination.valueOf(rs.getString("destination")),
                        rs.getString("channel"),
                        rs.getString("routing_key"),
                        rs.getString("payload")),
                batchSize));
        if (rows == null) {
            return 0;
        }
        for (OutboxRow row : rows) {
            publish(row);
            jdbcTemplate.update("UPDATE " + schema + ".outbox SET published_at = now() WHERE id = ?", row.id());
        }
        return rows.size();
    }

    /** Blocks until the broker actually confirms the send; a failure or timeout rolls back this batch (REQ-EVT-05). */
    private void publish(OutboxRow row) {
        switch (row.destination()) {
            case KAFKA -> {
                if (kafkaTemplate == null) {
                    throw new IllegalStateException("no KafkaTemplate configured for an outbox row bound for Kafka");
                }
                try {
                    kafkaTemplate.send(row.channel(), row.routingKey(), row.payload()).get(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while publishing outbox row " + row.id(), e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new IllegalStateException("failed to publish outbox row " + row.id() + " to Kafka", e);
                }
            }
            case RABBITMQ -> {
                if (rabbitTemplate == null) {
                    throw new IllegalStateException("no RabbitTemplate configured for an outbox row bound for RabbitMQ");
                }
                rabbitTemplate.convertAndSend(row.channel(), row.routingKey(), row.payload());
            }
        }
    }

    private record OutboxRow(UUID id, OutboxDestination destination, String channel, String routingKey, String payload) {
    }
}
