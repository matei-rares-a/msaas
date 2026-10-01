package com.saas.testsupport.events;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.saas.testsupport.containers.SharedContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/** Waits for messages published to Kafka or RabbitMQ, without fixed sleeps. */
public final class EventAssertions {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private EventAssertions() {
    }

    public static ConsumerRecord<String, String> awaitKafkaRecord(String topic, Predicate<ConsumerRecord<String, String>> matcher) {
        return awaitKafkaRecord(SharedContainers.kafka().getBootstrapServers(), topic, matcher, DEFAULT_TIMEOUT);
    }

    /** Reads the topic from the beginning with a fresh consumer group and returns the first matching record. */
    public static ConsumerRecord<String, String> awaitKafkaRecord(String bootstrapServers, String topic,
            Predicate<ConsumerRecord<String, String>> matcher, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "event-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            await().atMost(timeout).pollInterval(Duration.ZERO).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (matcher.test(record)) {
                        found.set(record);
                        return true;
                    }
                }
                return false;
            });
        }
        return found.get();
    }

    public static String awaitRabbitMessage(String queue, Predicate<String> matcher) {
        return awaitRabbitMessage(connectionFactory(SharedContainers.rabbitMq()), queue, matcher, DEFAULT_TIMEOUT);
    }

    /** Consumes from the queue until a matching body arrives; non-matching messages are acknowledged and dropped. */
    public static String awaitRabbitMessage(ConnectionFactory factory, String queue, Predicate<String> matcher, Duration timeout) {
        AtomicReference<String> found = new AtomicReference<>();
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
                GetResponse response;
                while ((response = channel.basicGet(queue, true)) != null) {
                    String body = new String(response.getBody(), StandardCharsets.UTF_8);
                    if (matcher.test(body)) {
                        found.set(body);
                        return true;
                    }
                }
                return false;
            });
        } catch (Exception e) {
            throw new IllegalStateException("Waiting for a RabbitMQ message on " + queue + " failed", e);
        }
        return Optional.ofNullable(found.get()).orElseThrow();
    }

    public static ConnectionFactory connectionFactory(RabbitMQContainer rabbit) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        return factory;
    }
}
