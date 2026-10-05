package com.saas.shared.events.consumer;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

/**
 * 5 attempts with a fixed backoff, then the message is republished as-is to the dead-letter exchange
 * and logged (REQ-EVT-07, the "RabbitMQ DLX" side of the requirement).
 */
public final class RabbitDeadLetterSupport {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitDeadLetterSupport.class);

    private RabbitDeadLetterSupport() {
    }

    public static MethodInterceptor retryInterceptor(RabbitOperations rabbitOperations,
            String deadLetterExchange, String deadLetterRoutingKey, long backOffMs) {
        RepublishMessageRecoverer recoverer = new LoggingRepublishMessageRecoverer(
                rabbitOperations, deadLetterExchange, deadLetterRoutingKey);
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(backOffMs, 1.0, backOffMs)
                .recoverer(recoverer)
                .build();
    }

    private static final class LoggingRepublishMessageRecoverer extends RepublishMessageRecoverer {
        LoggingRepublishMessageRecoverer(RabbitOperations rabbitOperations, String exchange, String routingKey) {
            super(rabbitOperations, exchange, routingKey);
        }

        @Override
        public void recover(Message message, Throwable cause) {
            LOG.warn("Moving RabbitMQ message to the dead-letter exchange after exhausted retries: {}", cause.toString());
            super.recover(message, cause instanceof ListenerExecutionFailedException ? cause.getCause() : cause);
        }
    }
}
