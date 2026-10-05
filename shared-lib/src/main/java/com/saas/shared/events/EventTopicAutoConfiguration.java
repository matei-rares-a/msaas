package com.saas.shared.events;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(EventTopicProperties.class)
public class EventTopicAutoConfiguration {
}
