package com.saas.shared.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Wires the single {@link JwtService} (P5.3), only for services that actually have a secret configured. */
@AutoConfiguration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock jwtFallbackClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(JwtService.class)
    @ConditionalOnProperty(prefix = "jwt", name = "secret")
    public JwtService jwtService(JwtProperties properties, Clock clock) {
        return new JwtService(properties.getSecret(), properties.getAccessTokenTtl(), clock);
    }
}
