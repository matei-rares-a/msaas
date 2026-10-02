package com.saas.notification;

import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** P1.4: global, unauthenticated feature flags are gone; per-tenant flags come in P8.3. */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class GlobalFeatureFlagsRemovedIT implements PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void featureFlagEndpointsAreGone() throws Exception {
        mockMvc.perform(get("/notifications/feature-flags")).andExpect(status().isNotFound());
        mockMvc.perform(patch("/notifications/feature-flags/{key}", "BETA_BILLING").param("enabled", "false"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noRouteExposesFeatureFlags() {
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.getPatternValues().stream().anyMatch(p -> p.contains("feature-flag")));
    }
}
