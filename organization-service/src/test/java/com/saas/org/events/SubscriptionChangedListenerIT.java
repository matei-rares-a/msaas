package com.saas.org.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.org.event.SubscriptionChangedPayload;
import com.saas.testsupport.containers.KafkaTestContainer;
import com.saas.testsupport.containers.SharedContainers;
import com.saas.testsupport.fixtures.TenantFixtures;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The first production Kafka consumer in the project, exercised against a real broker (REQ-BILL-03,
 * REQ-EVT-05, REQ-EVT-08). Same dedicated-topology-container setup as {@code AdminDemotionRequestIT},
 * combined with the shared Kafka broker ({@link KafkaTestContainer}) - and deliberately NOT disabling
 * {@code spring.kafka.listener.auto-startup}, unlike every other org-service IT: this is the one test
 * that needs {@link SubscriptionChangedListener} actually running.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // A brand-new consumer group has no committed offset; "latest" (the default) would make the
        // listener's very first partition assignment start right after whatever is already on the
        // topic - racing the test's own publish, which runs moments after the context starts. Same
        // fix KafkaDeadLetterIT uses for the same reason.
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "test.marker=SubscriptionChangedListenerIT"
})
class SubscriptionChangedListenerIT implements KafkaTestContainer {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final PostgreSQLContainer<?> postgres = startTopology();
    private static DataSource adminDataSource;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private KafkaProducer<String, String> producer;

    private static PostgreSQLContainer<?> startTopology() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(SharedContainers.POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withUsername("admin")
                .withPassword("admin")
                .withCopyFileToContainer(MountableFile.forHostPath(ROOT.resolve("db/init/01-topology.sh")),
                        "/docker-entrypoint-initdb.d/01-topology.sh")
                .withEnv("AUTH_DB_PASSWORD", "pw")
                .withEnv("ORG_DB_PASSWORD", "pw")
                .withEnv("BILLING_DB_PASSWORD", "pw")
                .withEnv("NOTIF_DB_PASSWORD", "pw")
                .withEnv("OUTBOX_RELAY_DB_PASSWORD", "pw")
                .withEnv("ORG_AUTH_READER_DB_PASSWORD", "pw");
        container.start();

        String shardUrl = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/saas_shard_1");
        System.setProperty("spring.datasource.url", shardUrl);
        System.setProperty("spring.datasource.username", "org_svc");
        System.setProperty("spring.datasource.password", "pw");
        System.setProperty("saas.org.auth-reader.datasource.url", shardUrl);
        System.setProperty("saas.org.auth-reader.datasource.username", "org_auth_reader");
        System.setProperty("saas.org.auth-reader.datasource.password", "pw");
        // Postgres superuser, bypasses RLS entirely - used only to set up fixtures and verify rows
        // directly, same role "admin" other org ITs already use for their own direct JDBC reads.
        adminDataSource = DataSourceBuilder.create().url(shardUrl).username("admin").password("admin").build();
        return container;
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(props);
    }

    @AfterEach
    void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    @Tag("REQ-BILL-03")
    void maxUsersProjectionUpdatesFromAPublishedEvent() throws Exception {
        TenantFixtures.TestTenant tenant = TenantFixtures.tenant("Projection Co");
        TenantFixtures.persist(adminDataSource, tenant);

        publish(tenant.id(), envelopeJson(tenant.id(), 25));

        // A generous margin over the design's nominal 10s: when the whole module's IT suite runs
        // together, this is the first test in the fork that actually needs the shared Kafka broker,
        // so the container start + the listener's first consumer-group join can themselves eat into
        // the window before any message is even delivered.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(maxUsersOf(tenant.id())).isEqualTo(25));
    }

    @Test
    @Tag("REQ-EVT-05")
    void redeliveringTheSameEventIdDoesNotReapplyADifferentValue() throws Exception {
        TenantFixtures.TestTenant tenant = TenantFixtures.tenant("Idempotent Co");
        TenantFixtures.persist(adminDataSource, tenant);

        String json = envelopeJson(tenant.id(), 10);
        publish(tenant.id(), json);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(maxUsersOf(tenant.id())).isEqualTo(10));

        // Same eventId, different maxUsers in the payload - must be skipped, not reapplied (REQ-EVT-05).
        String redelivered = json.replaceFirst("\"maxUsers\":\\d+", "\"maxUsers\":99");
        publish(tenant.id(), redelivered);

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(maxUsersOf(tenant.id())).isEqualTo(10));
    }

    @Test
    @Tag("REQ-EVT-08")
    void anEventForAnUnknownTenantIsLoggedAndSkipped() throws Exception {
        UUID unknownTenantId = UUID.randomUUID();

        publish(unknownTenantId, envelopeJson(unknownTenantId, 5));

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(tenantRowCount(unknownTenantId)).isZero());
    }

    /** Builds and serializes an {@code EventEnvelope<SubscriptionChangedPayload>} in one step - the
     * envelope type never crosses a method boundary here (it is constructed and immediately
     * serialized within this single method), which sidesteps an observed Maven-reactor compile issue
     * where a reactor-sibling generic type used as an explicit parameter/return type elsewhere in this
     * test module's test sources was erased to an unqualified binary name in the class file. */
    private String envelopeJson(UUID tenantId, int maxUsers) throws Exception {
        var envelope = com.saas.shared.events.EventEnvelope.forTenant("SUBSCRIPTION_CHANGED", 1, tenantId,
                new SubscriptionChangedPayload(tenantId, UUID.randomUUID(), maxUsers, "ACTIVE"));
        return objectMapper.writeValueAsString(envelope);
    }

    private void publish(UUID tenantId, String json) throws Exception {
        producer.send(new ProducerRecord<>("billing.events", tenantId.toString(), json)).get(10, TimeUnit.SECONDS);
    }

    private Integer maxUsersOf(UUID tenantId) {
        return new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT max_users FROM org.tenants WHERE id = ?", Integer.class, tenantId);
    }

    private int tenantRowCount(UUID tenantId) {
        return new JdbcTemplate(adminDataSource).queryForObject(
                "SELECT count(*) FROM org.tenants WHERE id = ?", Integer.class, tenantId);
    }
}
