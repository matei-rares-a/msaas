package com.saas.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AuthClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock authClock() {
        return Clock.systemUTC();
    }
}
