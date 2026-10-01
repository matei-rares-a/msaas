package com.saas.testsupport.containers;

import com.saas.testsupport.events.EventAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: every shared container starts once and Spring connects to it through @ServiceConnection. */
@SpringBootTest(classes = SharedContainersIT.SmokeApp.class)
class SharedContainersIT implements PostgresTestContainer, KafkaTestContainer, RabbitMqTestContainer, RedisTestContainer {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    void containersAreSingletonsAndRunning() {
        assertThat(SharedContainers.postgres()).isSameAs(POSTGRES);
        assertThat(SharedContainers.kafka()).isSameAs(KAFKA);
        assertThat(SharedContainers.rabbitMq()).isSameAs(RABBITMQ);
        assertThat(SharedContainers.redis()).isSameAs(REDIS);
        assertThat(POSTGRES.isRunning() && KAFKA.isRunning() && RABBITMQ.isRunning() && REDIS.isRunning()).isTrue();
    }

    @Test
    void postgresIsWired() {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(POSTGRES.getDatabaseName());
    }

    @Test
    void kafkaIsWired() throws Exception {
        String topic = "smoke-" + UUID.randomUUID();
        String value = UUID.randomUUID().toString();

        kafka.send(topic, "key", value).get();

        assertThat(EventAssertions.awaitKafkaRecord(topic, r -> value.equals(r.value())).key()).isEqualTo("key");
    }

    @Test
    void rabbitMqIsWired() {
        String queue = "smoke-" + UUID.randomUUID();
        String body = UUID.randomUUID().toString();
        amqpAdmin.declareQueue(new Queue(queue, false));

        rabbit.convertAndSend(queue, body);

        assertThat(EventAssertions.awaitRabbitMessage(queue, body::equals)).isEqualTo(body);
    }

    @Test
    void redisIsWired() {
        redis.opsForValue().set("smoke", "ok");

        assertThat(redis.opsForValue().get("smoke")).isEqualTo("ok");
        assertThat(((LettuceConnectionFactory) redis.getConnectionFactory()).getPort()).isEqualTo(REDIS.getMappedPort(6379));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SmokeApp {
    }
}
