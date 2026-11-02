package com.saas.notification;

import com.saas.testsupport.containers.SharedContainers;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.List;
import java.util.Map;

/**
 * Creates the topics this service listens on before the Spring context starts. A listener that
 * subscribes to a topic that does not exist yet only notices it after the consumer's metadata refresh
 * (minutes), which would make a test wait; every IT that starts a context with the listeners running
 * calls this first, because the context (and so the subscription) is shared between those classes.
 */
final class KafkaTestTopics {

    static final List<String> LISTENED = List.of("org.events", "billing.events", "auth.events");

    private KafkaTestTopics() {
    }

    static void ensureListenedTopicsExist() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.kafka().getBootstrapServers()))) {
            for (String topic : LISTENED) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
                } catch (Exception e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
    }
}
