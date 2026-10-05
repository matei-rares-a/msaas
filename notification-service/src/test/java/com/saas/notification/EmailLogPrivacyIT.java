package com.saas.notification;

import com.saas.notification.entity.EmailLog;
import com.saas.notification.service.NotificationService;
import com.saas.shared.tenancy.TenantContext;
import com.saas.testsupport.containers.PostgresTestContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class EmailLogPrivacyIT implements PostgresTestContainer {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Tag("REQ-NOTIF-07")
    void emailSearchByAddressEndpointIsGone() throws Exception {
        mockMvc.perform(get("/notifications/emails-by/{email}", "victim@test.local"))
                .andExpect(status().isNotFound());

        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.getPatternValues().stream().anyMatch(p -> p.contains("emails")));
    }

    @Test
    @Tag("REQ-NOTIF-06")
    void emailLogTableHasNoBodyColumn() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = 'notif' and table_name = 'email_logs'",
                String.class);

        assertThat(columns).contains("to_email", "subject", "status", "event_type", "created_at").doesNotContain("body");
        assertThat(Arrays.stream(EmailLog.class.getDeclaredFields()).map(f -> f.getName())).doesNotContain("body");
    }

    @Test
    @Tag("REQ-NOTIF-06")
    void secretsFromEmailContentAreNeverStored() {
        UUID tenantId = UUID.randomUUID();
        String inviteToken = "invite-" + UUID.randomUUID();
        String inviteAddress = "invite-" + UUID.randomUUID() + "@test.local";

        TenantContext.runWith(tenantId, () -> notificationService.sendInvitationEmail(inviteAddress, "Acme", inviteToken));

        List<String> storedRows = jdbc.queryForList(
                "select row_to_json(e)::text from notif.email_logs e where to_email = ?",
                String.class, inviteAddress);
        assertThat(storedRows).hasSize(1);
        assertThat(storedRows).noneMatch(row -> row.contains(inviteToken));
    }
}
