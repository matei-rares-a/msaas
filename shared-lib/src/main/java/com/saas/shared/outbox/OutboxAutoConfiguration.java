package com.saas.shared.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;

/**
 * Wires {@link OutboxWriter} (always, once {@code saas.outbox.enabled=true}) and, once a relay
 * datasource is configured, {@link OutboxRelay} plus the scheduler that drives it.
 */
@AutoConfiguration
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "saas.outbox", name = "enabled", havingValue = "true")
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    public OutboxWriter outboxWriter(DataSource dataSource) {
        return new OutboxWriter(dataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "saas.outbox.relay", name = "enabled", havingValue = "true")
    @ConditionalOnClass(KafkaTemplate.class)
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        var props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    @ConditionalOnProperty(prefix = "saas.outbox.relay", name = "enabled", havingValue = "true")
    public OutboxRelay outboxRelay(Environment environment,
                                    @Qualifier("outboxKafkaTemplate") ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                                    ApplicationContext context,
                                    @org.springframework.beans.factory.annotation.Value("${saas.outbox.relay.schema}") String schema) {
        // The relay's connection (a role of its own, which bypasses RLS) is deliberately not a bean: a second DataSource in the
        // context makes the service's own ambiguous, and JPA and Flyway then refuse to configure themselves. The relay owns the
        // pool and closes it with the context.
        DataSource relayDataSource = DataSourceBuilder.create()
                .url(environment.getRequiredProperty("saas.outbox.relay.datasource.url"))
                .username(environment.getRequiredProperty("saas.outbox.relay.datasource.username"))
                .password(environment.getRequiredProperty("saas.outbox.relay.datasource.password"))
                .build();
        return new OutboxRelay(relayDataSource, schema, kafkaTemplate.getIfAvailable(), RabbitLookup.find(context));
    }

    @Bean
    @ConditionalOnProperty(prefix = "saas.outbox.relay", name = "enabled", havingValue = "true")
    public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay) {
        return new OutboxRelayScheduler(outboxRelay);
    }

    /** A service without RabbitMQ on its classpath (auth-service) must be able to run the relay: no signature of the configuration class itself may mention RabbitTemplate, so the lookup lives here. */
    private static final class RabbitLookup {
        static OutboxRelay.RabbitPublisher find(ApplicationContext context) {
            if (!ClassUtils.isPresent("org.springframework.amqp.rabbit.core.RabbitTemplate", context.getClassLoader())) {
                return null;
            }
            RabbitTemplate template = context.getBeanProvider(RabbitTemplate.class).getIfAvailable();
            return template == null ? null : template::convertAndSend;
        }
    }

    /** Separate so the poll interval/batch size are plain {@code @Scheduled} config, not buried in the relay. */
    public static class OutboxRelayScheduler {
        private final OutboxRelay relay;

        OutboxRelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${saas.outbox.relay.poll-interval-ms:500}")
        public void pollAndPublish() {
            relay.pollAndPublishOnce(100);
        }
    }
}
