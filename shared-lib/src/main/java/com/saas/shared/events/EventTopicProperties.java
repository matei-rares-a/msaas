package com.saas.shared.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Default Kafka topic retention for domain events = the activity journal's retention (REQ-EVT-03, REQ-MON-03). */
@ConfigurationProperties(prefix = "saas.events")
public class EventTopicProperties {

    private Duration retention = Duration.ofDays(180);

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }
}
