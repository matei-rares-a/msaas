package com.saas.notification.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogReader;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.shared.events.consumer.RabbitDeadLetterSupport;
import com.saas.shared.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the CSV export of the activity journal (REQ-MON-05, REQ-SCALE-03): the service, the RabbitMQ
 * topology of the background job (exchange, work queue, dead-letter exchange and queue) and the worker's
 * listener container with retries. The thresholds are defaults in {@code application.yml}
 * ({@code saas.export.*}), overridable per environment.
 */
@Configuration
public class ExportConfig {

    static final String QUEUE = "notif.export-jobs";
    static final String DEAD_LETTER_EXCHANGE = "notif.export-commands.dlx";
    static final String DEAD_LETTER_QUEUE = "notif.export-jobs.dlq";
    private static final String DEAD_LETTER_KEY = "export-jobs";

    @Bean
    public ExportTimings exportTimings(@Value("${saas.export.timing-window:20}") int window,
            @Value("${saas.export.default-millis-per-row:0.05}") double defaultMillisPerRow, MeterRegistry meterRegistry) {
        return new ExportTimings(window, defaultMillisPerRow, meterRegistry);
    }

    @Bean
    public ActivityLogExportService activityLogExportService(ActivityLogReader reader, DataSource dataSource, ExportTimings timings,
            OutboxWriter outboxWriter, InAppNotificationRepository notifications, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${saas.export.async-threshold:PT5S}") Duration asyncThreshold,
            @Value("${saas.export.max-rows:500000}") long maxRows,
            @Value("${saas.export.retention:PT24H}") Duration retention) {
        return new ActivityLogExportService(reader, new ExportJobStore(dataSource), timings, outboxWriter, notifications,
                objectMapper, transactionManager, clock, asyncThreshold, maxRows, retention);
    }

    @Bean
    public ExportJobListener exportJobListener(ActivityLogExportService exportService, ObjectMapper objectMapper) {
        return new ExportJobListener(exportService, objectMapper);
    }

    @Bean
    public FanoutExchange exportCommandExchange() {
        return new FanoutExchange(ActivityLogExportService.COMMAND_EXCHANGE);
    }

    @Bean
    public Queue exportJobsQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding exportJobsBinding(FanoutExchange exportCommandExchange, Queue exportJobsQueue) {
        return BindingBuilder.bind(exportJobsQueue).to(exportCommandExchange);
    }

    @Bean
    public DirectExchange exportDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue exportDeadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE, true);
    }

    @Bean
    public Binding exportDeadLetterBinding(DirectExchange exportDeadLetterExchange, Queue exportDeadLetterQueue) {
        return BindingBuilder.bind(exportDeadLetterQueue).to(exportDeadLetterExchange).with(DEAD_LETTER_KEY);
    }

    /** Built through Boot's configurer so {@code spring.rabbitmq.listener.simple.*} (e.g. auto-startup) still applies. */
    @Bean
    public SimpleRabbitListenerContainerFactory exportListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate,
            @Value("${saas.export.retry-backoff-ms:1000}") long backOffMs) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(RabbitDeadLetterSupport.retryInterceptor(rabbitTemplate, DEAD_LETTER_EXCHANGE, DEAD_LETTER_KEY, backOffMs));
        return factory;
    }
}
