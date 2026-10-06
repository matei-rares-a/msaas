package com.saas.billing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class BillingClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock billingClock() {
        return Clock.systemUTC();
    }
}
