package com.saas.notification.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.activity.ActivityLogWriter;
import com.saas.notification.repository.EmailLogRepository;
import com.saas.shared.events.consumer.RabbitDeadLetterSupport;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the sending of emails (REQ-NOTIF-06..09): the RabbitMQ topology (one work queue fed by the command
 * exchanges the producers publish to, plus a dead-letter exchange and queue), the consumer, the provider and
 * the retry policy. The knobs are defaults in {@code application.yml} ({@code saas.email.*}). There is
 * deliberately no setting a user can change: transactional and security emails cannot be turned off
 * (REQ-NOTIF-09).
 */
@Configuration
public class EmailConfig {

    static final String QUEUE = "notif.email-commands";
    static final String DEAD_LETTER_EXCHANGE = "notif.email-commands.dlx";
    static final String DEAD_LETTER_QUEUE = "notif.email-commands.dlq";
    private static final String DEAD_LETTER_KEY = "email-commands";

    /**
     * The exchanges the outbox relays publish email commands to - the producer's {@code <service>.commands}
     * channel - each bound to the one work queue. Fanout, because the routing key the relay sends is a tenant
     * or user id and means nothing here. A producer's relay can publish only once its exchange exists, so this
     * service declares them when it starts.
     */
    @Bean
    public Declarables emailCommandTopology(@Value("${saas.email.command-exchanges:org.commands,auth.commands,billing.commands}") List<String> exchanges) {
        Queue queue = new Queue(QUEUE, true);
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(queue);
        for (String name : exchanges) {
            FanoutExchange exchange = new FanoutExchange(name);
            declarables.add(exchange);
            declarables.add(BindingBuilder.bind(queue).to(exchange));
        }
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE);
        Queue deadLetterQueue = new Queue(DEAD_LETTER_QUEUE, true);
        declarables.add(deadLetterExchange);
        declarables.add(deadLetterQueue);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_KEY);
        declarables.add(deadLetterBinding);
        return new Declarables(declarables);
    }

    @Bean
    @ConditionalOnProperty(prefix = "saas.email", name = "provider", havingValue = "simulated", matchIfMissing = true)
    public SimulatedEmailProvider simulatedEmailProvider(
            @Value("${saas.email.simulated.failure-mode:ALWAYS_SUCCEED}") SimulatedEmailProvider.FailureMode failureMode) {
        return new SimulatedEmailProvider(failureMode);
    }

    @Bean
    public EmailSender.Sleeper emailRetrySleeper() {
        return duration -> Thread.sleep(duration.toMillis());
    }

    @Bean
    public EmailSender emailSender(EmailProvider emailProvider, EmailSender.Sleeper emailRetrySleeper,
            @Value("${saas.email.max-retries:3}") int maxRetries,
            @Value("${saas.email.initial-backoff:PT1S}") Duration initialBackoff) {
        return new EmailSender(emailProvider, emailRetrySleeper, maxRetries, initialBackoff);
    }

    @Bean
    EmailDeliveryService emailDeliveryService(EmailSender emailSender, EmailLogRepository emailLogRepository,
            ActivityLogWriter activityLogWriter, PlatformTransactionManager transactionManager) {
        return new EmailDeliveryService(emailSender, emailLogRepository, activityLogWriter, transactionManager);
    }

    @Bean
    public EmailCommandListener emailCommandListener(EmailDeliveryService emailDeliveryService, ObjectMapper objectMapper,
            @Value("${saas.email.app-base-url:http://localhost:4200}") String appBaseUrl,
            @Value("${saas.email.support-contact:support@example.com}") String supportContact) {
        return new EmailCommandListener(emailDeliveryService, new EmailTemplates(appBaseUrl, supportContact), objectMapper);
    }

    /** Built through Boot's configurer so {@code spring.rabbitmq.listener.simple.*} (e.g. auto-startup) still applies. */
    @Bean
    public SimpleRabbitListenerContainerFactory emailListenerContainerFactory(SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate,
            @Value("${saas.email.listener-retry-backoff-ms:1000}") long backOffMs,
            @Value("${saas.email.listener-auto-startup:true}") boolean autoStartup) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(RabbitDeadLetterSupport.retryInterceptor(rabbitTemplate, DEAD_LETTER_EXCHANGE, DEAD_LETTER_KEY, backOffMs));
        if (!autoStartup) {
            factory.setAutoStartup(false); // lets a test context keep this consumer off a queue another context shares
        }
        return factory;
    }
}
