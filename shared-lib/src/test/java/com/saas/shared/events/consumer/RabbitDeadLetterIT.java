package com.saas.shared.events.consumer;

import com.saas.testsupport.containers.RabbitMqTestContainer;
import com.saas.testsupport.events.EventAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A listener that always throws is retried 5 times with backoff, then the message is republished to
 * the dead-letter exchange and the failures are logged (REQ-EVT-07, "RabbitMQ DLX" side).
 */
@SpringBootTest(classes = RabbitDeadLetterIT.BrokerApp.class)
class RabbitDeadLetterIT implements RabbitMqTestContainer {

    @Autowired
    private ConnectionFactory connectionFactory;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;

    private SimpleMessageListenerContainer container;

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Tag("REQ-EVT-07")
    void aListenerThatAlwaysFailsRepublishesToTheDeadLetterExchangeAfterFiveAttempts() {
        String queue = "dlx-it-" + UUID.randomUUID();
        String deadLetterExchange = "dlx-it-exchange-" + UUID.randomUUID();
        String deadLetterQueue = "dlx-it-dead-" + UUID.randomUUID();
        String payload = UUID.randomUUID().toString();
        AtomicInteger attempts = new AtomicInteger();

        amqpAdmin.declareQueue(new Queue(queue, false));
        amqpAdmin.declareQueue(new Queue(deadLetterQueue, false));
        DirectExchange exchange = new DirectExchange(deadLetterExchange, false, false);
        amqpAdmin.declareExchange(exchange);
        Binding binding = BindingBuilder.bind(new Queue(deadLetterQueue, false)).to(exchange).with(deadLetterQueue);
        amqpAdmin.declareBinding(binding);

        container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(queue);
        container.setMessageListener(message -> {
            attempts.incrementAndGet();
            throw new RuntimeException("always fails");
        });
        container.setAdviceChain(RabbitDeadLetterSupport.retryInterceptor(
                rabbitTemplate, deadLetterExchange, deadLetterQueue, 20L));
        container.start();

        rabbitTemplate.convertAndSend(queue, payload);

        assertThat(EventAssertions.awaitRabbitMessage(deadLetterQueue, payload::equals)).isEqualTo(payload);
        assertThat(attempts).hasValue(5);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
    static class BrokerApp {
    }
}
