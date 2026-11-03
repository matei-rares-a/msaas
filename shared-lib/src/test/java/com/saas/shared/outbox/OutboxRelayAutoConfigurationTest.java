package com.saas.shared.outbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The outbox relay publishes a service's events (REQ-EVT-04), and when it is switched on it must not change anything else in the
 * service. Its connection, a role of its own, used to be a second {@code DataSource} bean, which made the service's own ambiguous:
 * JPA and Flyway then did not configure themselves and the service did not start. Nothing had ever started a service with the relay
 * on until the end-to-end stack did (P12.1).
 */
@Tag("REQ-EVT-04")
class OutboxRelayAutoConfigurationTest {

    @Configuration
    @EnableConfigurationProperties(KafkaProperties.class)
    static class TheServicesOwn {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withUserConfiguration(TheServicesOwn.class)
            .withPropertyValues("saas.outbox.enabled=true");

    private static final String[] RELAY_ON = {
            "saas.outbox.relay.enabled=true",
            "saas.outbox.relay.schema=org",
            "saas.outbox.relay.datasource.url=jdbc:postgresql://relay-host:5432/shard",
            "saas.outbox.relay.datasource.username=outbox_relay",
            "saas.outbox.relay.datasource.password=secret"
    };

    @Test
    void withTheRelayOnTheServiceStillHasExactlyOneDataSource() {
        runner.withPropertyValues(RELAY_ON).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataSource.class)).as("the relay's pool is not a bean").hasSize(1).containsKey("dataSource");
            assertThat(context).hasSingleBean(OutboxRelay.class).hasSingleBean(OutboxAutoConfiguration.OutboxRelayScheduler.class);
        });
    }

    @Test
    void theRelayReadsItsConnectionFromItsOwnSettings() {
        runner.withPropertyValues(RELAY_ON).run(context -> {
            assertThat(context).hasNotFailed();
            // a pool that is not started yet: the settings it was given are what it will connect with
            var field = OutboxRelay.class.getDeclaredField("relayDataSource");
            field.setAccessible(true);
            var pool = (com.zaxxer.hikari.HikariDataSource) field.get(context.getBean(OutboxRelay.class));
            assertThat(pool.getJdbcUrl()).isEqualTo("jdbc:postgresql://relay-host:5432/shard");
            assertThat(pool.getUsername()).isEqualTo("outbox_relay");
        });
    }

    @Test
    void theRelayClosesItsPoolWhenTheApplicationStops() {
        var closed = new java.util.concurrent.atomic.AtomicReference<com.zaxxer.hikari.HikariDataSource>();
        runner.withPropertyValues(RELAY_ON).run(context -> {
            var field = OutboxRelay.class.getDeclaredField("relayDataSource");
            field.setAccessible(true);
            closed.set((com.zaxxer.hikari.HikariDataSource) field.get(context.getBean(OutboxRelay.class)));
            assertThat(closed.get().isClosed()).isFalse();
        });

        assertThat(closed.get().isClosed()).isTrue();
    }

    @Test
    void aServiceWithoutRabbitMqOnItsClasspathCanRunTheRelay() {
        runner.withPropertyValues(RELAY_ON).withClassLoader(new FilteredClassLoader(RabbitTemplate.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxRelay.class);
        });
    }

    @Test
    void neitherTheRelayNorItsConfigurationMentionRabbitTemplateInASignature() {
        // Spring introspects these classes; in a service without RabbitMQ on its classpath (auth-service) a signature naming
        // RabbitTemplate fails with NoClassDefFoundError and the service does not start. A FilteredClassLoader cannot show this.
        for (Class<?> type : new Class<?>[]{OutboxRelay.class, OutboxAutoConfiguration.class}) {
            var signatures = new java.util.ArrayList<Class<?>>();
            for (var c : type.getDeclaredConstructors()) {
                signatures.addAll(java.util.List.of(c.getParameterTypes()));
            }
            for (var m : type.getDeclaredMethods()) {
                signatures.addAll(java.util.List.of(m.getParameterTypes()));
                signatures.add(m.getReturnType());
            }
            for (var f : type.getDeclaredFields()) {
                signatures.add(f.getType());
            }
            assertThat(signatures).as(type.getSimpleName()).doesNotContain(RabbitTemplate.class);
        }
    }

    @Test
    void withoutTheRelayThereIsOnlyTheWriter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxWriter.class).doesNotHaveBean(OutboxRelay.class);
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
        });
    }
}
