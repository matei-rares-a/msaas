package com.saas.notification;

import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
@Tag("REQ-NOTIF-02")
class PublicNotificationCreationRemovedIT implements PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void postingANotificationIsRejectedAndCreatesNothing() throws Exception {
        String body = """
                {"userId": "%s", "title": "Phishing", "message": "Click here", "level": "INFO"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/notifications/in-app")
                        .header("X-Org-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        Integer rows = jdbc.queryForObject("select count(*) from notif.in_app_notifications", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void noEndpointCreatesResources() {
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.getMethodsCondition().getMethods().stream()
                        .anyMatch(Set.of(RequestMethod.POST, RequestMethod.PUT)::contains));
    }
}
