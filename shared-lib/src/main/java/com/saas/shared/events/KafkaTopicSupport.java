package com.saas.shared.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/** Declares a Kafka topic with an explicit retention (REQ-EVT-03): topic retention = journal retention. */
public final class KafkaTopicSupport {

    private KafkaTopicSupport() {
    }

    public static NewTopic topic(String name, int partitions, int replicas, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retention.toMillis()))
                .build();
    }
}
