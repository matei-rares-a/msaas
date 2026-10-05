package com.saas.shared.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
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
    @ConfigurationProperties(prefix = "saas.outbox.relay.datasource")
    public DataSource outboxRelayDataSource() {
        return DataSourceBuilder.create().build();
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
    public OutboxRelay outboxRelay(DataSource outboxRelayDataSource,
                                    ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate,
                                    ObjectProvider<RabbitTemplate> rabbitTemplate,
                                    @org.springframework.beans.factory.annotation.Value("${saas.outbox.relay.schema}") String schema) {
        return new OutboxRelay(outboxRelayDataSource, schema,
                kafkaTemplate.getIfAvailable(), rabbitTemplate.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "saas.outbox.relay", name = "enabled", havingValue = "true")
    public OutboxRelayScheduler outboxRelayScheduler(OutboxRelay outboxRelay) {
        return new OutboxRelayScheduler(outboxRelay);
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
