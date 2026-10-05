package com.saas.shared.outbox;

import com.saas.testsupport.containers.KafkaTestContainer;
import com.saas.testsupport.containers.RabbitMqTestContainer;
import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.events.EventAssertions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real outbox contract end to end: a write inside the business transaction, the relay
 * (separate, BYPASSRLS role) publishing it, and the matching broker receiving it (REQ-EVT-01, REQ-EVT-04).
 * Kafka/RabbitMQ come from the shared containers via {@code @ServiceConnection}, like SharedContainersIT;
 * Postgres is its own container because this test needs custom roles/RLS, not the shared one.
 */
@SpringBootTest(classes = OutboxWriterRelayIT.BrokerApp.class)
class OutboxWriterRelayIT implements KafkaTestContainer, RabbitMqTestContainer {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;

    private static PostgreSQLContainer<?> postgres;
    private static DataSource appDataSource;
    private static DataSource relayDataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin");
        postgres.start();

        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), "admin", "admin");
                Statement st = admin.createStatement()) {
            st.execute("CREATE ROLE app_svc LOGIN PASSWORD 'pw'");
            st.execute("CREATE ROLE outbox_relay LOGIN BYPASSRLS PASSWORD 'pw'");
            st.execute("CREATE SCHEMA app AUTHORIZATION app_svc");
            st.execute("ALTER ROLE app_svc SET search_path = app");
        }
        try (Connection owner = DriverManager.getConnection(postgres.getJdbcUrl(), "app_svc", "pw");
                Statement st = owner.createStatement()) {
            st.execute("CREATE TABLE outbox (" +
                    " id UUID PRIMARY KEY, tenant_id UUID NOT NULL, event_type VARCHAR(255) NOT NULL," +
                    " destination VARCHAR(20) NOT NULL CHECK (destination IN ('KAFKA', 'RABBITMQ'))," +
                    " channel VARCHAR(255) NOT NULL, routing_key VARCHAR(255) NOT NULL, payload TEXT NOT NULL," +
                    " created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), published_at TIMESTAMPTZ, UNIQUE (tenant_id, id))");
            st.execute("ALTER TABLE outbox ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE outbox FORCE ROW LEVEL SECURITY");
            st.execute("CREATE POLICY tenant_isolation ON outbox" +
                    " USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)" +
                    " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::uuid)");
            st.execute("GRANT USAGE ON SCHEMA app TO outbox_relay");
            st.execute("GRANT SELECT, UPDATE ON outbox TO outbox_relay");
        }

        appDataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
                .url(postgres.getJdbcUrl()).username("app_svc").password("pw").build();
        relayDataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
                .url(postgres.getJdbcUrl()).username("outbox_relay").password("pw").build();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private void writeInTenantTransaction(UUID tenantId, boolean rollback, OutboxDestination destination,
            String channel, String routingKey, String payload) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        tx.executeWithoutResult(status -> {
            new JdbcTemplate(appDataSource).execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            new OutboxWriter(appDataSource).write(tenantId, "TEST_EVENT", destination, channel, routingKey, payload);
            if (rollback) {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    @Tag("REQ-EVT-04")
    void aRolledBackTransactionPublishesNothing() {
        UUID tenantId = UUID.randomUUID();
        writeInTenantTransaction(tenantId, true, OutboxDestination.KAFKA, "outbox-it-unused", tenantId.toString(), "{}");

        OutboxRelay relay = new OutboxRelay(relayDataSource, "app", kafkaTemplate, null);
        assertThat(relay.pollAndPublishOnce(10)).isZero();
    }

    @Test
    @Tag("REQ-EVT-01")
    @Tag("REQ-EVT-04")
    void aCommittedTransactionPublishesExactlyOnceToKafka() {
        UUID tenantId = UUID.randomUUID();
        String topic = "outbox-it-" + UUID.randomUUID();
        String payload = "{\"tenantId\":\"" + tenantId + "\"}";
        writeInTenantTransaction(tenantId, false, OutboxDestination.KAFKA, topic, tenantId.toString(), payload);

        OutboxRelay relay = new OutboxRelay(relayDataSource, "app", kafkaTemplate, null);
        assertThat(relay.pollAndPublishOnce(10)).isEqualTo(1);

        ConsumerRecord<String, String> record = EventAssertions.awaitKafkaRecord(topic, r -> payload.equals(r.value()));
        assertThat(record.key()).isEqualTo(tenantId.toString());

        // already published -> a second poll finds nothing left to send
        assertThat(relay.pollAndPublishOnce(10)).isZero();
    }

    @Test
    @Tag("REQ-EVT-01")
    @Tag("REQ-EVT-04")
    void aCommittedTransactionPublishesExactlyOnceToRabbit() {
        UUID tenantId = UUID.randomUUID();
        String queue = "outbox-it-" + UUID.randomUUID();
        String payload = "{\"tenantId\":\"" + tenantId + "\"}";
        amqpAdmin.declareQueue(new Queue(queue, false));

        // default exchange ("") routes to the queue named by the routing key
        writeInTenantTransaction(tenantId, false, OutboxDestination.RABBITMQ, "", queue, payload);

        OutboxRelay relay = new OutboxRelay(relayDataSource, "app", null, rabbitTemplate);
        assertThat(relay.pollAndPublishOnce(10)).isEqualTo(1);

        assertThat(EventAssertions.awaitRabbitMessage(queue, payload::equals)).isEqualTo(payload);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
    static class BrokerApp {
    }
}
