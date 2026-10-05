package com.saas.shared.events;

import com.saas.testsupport.containers.SharedContainers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The declared topic's retention matches the configured value (REQ-EVT-03: topic retention = journal retention). */
class KafkaTopicSupportIT {

    @Test
    @Tag("REQ-EVT-03")
    void theDeclaredTopicHasTheConfiguredRetention() throws Exception {
        String topic = "topic-support-it-" + UUID.randomUUID();
        Duration testRetention = Duration.ofMinutes(10);

        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers()))) {
            admin.createTopics(List.of(KafkaTopicSupport.topic(topic, 1, 1, testRetention))).all().get();

            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Config config = admin.describeConfigs(List.of(resource)).all().get().get(resource);

            assertThat(config.get(TopicConfig.RETENTION_MS_CONFIG).value())
                    .isEqualTo(String.valueOf(testRetention.toMillis()));
        }
    }
}
