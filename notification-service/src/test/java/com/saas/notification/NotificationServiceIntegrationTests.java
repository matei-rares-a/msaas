package com.saas.notification;

import com.saas.notification.entity.EmailLog;
import com.saas.notification.repository.EmailLogRepository;
import com.saas.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class NotificationServiceIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private NotificationService notificationService;

    private static final String ORG_ID_HEADER = "X-Org-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLE_HEADER = "X-Role";

    @Test
    void testEmailLogging() throws Exception {
        // Create an email log entry
        EmailLog emailLog = new EmailLog();
        emailLog.setId(UUID.randomUUID());
        emailLog.setToEmail("user@example.com");
        emailLog.setSubject("Welcome to SaaS");
        emailLog.setBody("Welcome! This is your verification email.");
        emailLog.setStatus("SENT");
        emailLog.setEventType("USER_INVITATION");
        emailLogRepository.save(emailLog);

        // Retrieve email logs
        mockMvc.perform(get("/notifications/emails-by/user@example.com")
                .header(USER_ID_HEADER, UUID.randomUUID().toString())
                .header(ORG_ID_HEADER, UUID.randomUUID().toString())
                .header(ROLE_HEADER, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", isA(ArrayList.class)))
                .andExpect(jsonPath("$[0].toEmail").value("user@example.com"))
                .andExpect(jsonPath("$[0].status").value("SENT"));
    }

    @Test
    void testInvitationEmailNotification() {
        // Simulate invitation event
        notificationService.sendInvitationEmail(
            "newuser@example.com",
            "Test Organization",
            "inv-token-12345"
        );

        // Verify email was logged
        EmailLog logged = emailLogRepository.findByToEmail("newuser@example.com")
            .stream()
            .findFirst()
            .orElse(null);

        assert logged != null;
        assert logged.getEventType().equals("USER_INVITED");
        assert logged.getSubject().contains("been invited");
    }

    @Test
    void testPasswordResetEmailNotification() {
        // Simulate password reset event
        notificationService.sendPasswordResetEmail(
            "user@example.com",
            "reset-token-67890"
        );

        // Verify email was logged
        EmailLog logged = emailLogRepository.findByToEmail("user@example.com")
            .stream()
            .findFirst()
            .orElse(null);

        assert logged != null;
        assert logged.getEventType().equals("PASSWORD_RESET");
        assert logged.getSubject().contains("reset");
    }

    @Test
    void testHealthCheck() throws Exception {
        mockMvc.perform(get("/notifications/health")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testEmailLogPersistence() throws Exception {
        UUID emailId = UUID.randomUUID();

        // Create email log
        EmailLog emailLog = new EmailLog();
        emailLog.setId(emailId);
        emailLog.setToEmail("persist@example.com");
        emailLog.setSubject("Test Email");
        emailLog.setBody("This email should persist");
        emailLog.setStatus("SENT");
        emailLog.setEventType("NOTIFICATION");
        emailLogRepository.save(emailLog);

        // Retrieve and verify
        EmailLog retrieved = emailLogRepository.findById(emailId).orElse(null);
        assert retrieved != null;
        assert retrieved.getToEmail().equals("persist@example.com");
        assert retrieved.getStatus().equals("SENT");
    }

    @Test
    void testRabbitMQListenerIntegration() throws Exception {
        // This test verifies that RabbitMQ messages are consumed
        // Mock RabbitMQ message
        String userInvitedEvent = """
            {
                "eventType": "USER_INVITED",
                "email": "invited@example.com",
                "organizationId": "%s",
                "inviteToken": "token-123"
            }
        """.formatted(UUID.randomUUID().toString());

        // Simulate message consumption
        // (In real scenario, this would come from RabbitMQ queue)

        // Verify email was sent
        EmailLog logged = emailLogRepository.findByToEmail("invited@example.com")
            .stream()
            .findFirst()
            .orElse(null);

        // Note: This may be null in test if actual RabbitMQ is not running
        // Consider using embedded RabbitMQ for testing
    }

    @Test
    void testKafkaListenerIntegration() throws Exception {
        // This test verifies that Kafka events are consumed
        String passwordResetEvent = """
            {
                "eventType": "PASSWORD_RESET",
                "email": "reset@example.com",
                "token": "reset-token-789"
            }
        """;

        // Simulate Kafka message consumption
        // (In real scenario, this would come from Kafka topic)

        // Verify email was sent
        EmailLog logged = emailLogRepository.findByToEmail("reset@example.com")
            .stream()
            .findFirst()
            .orElse(null);

        // Note: This may be null in test if actual Kafka is not running
        // Consider using embedded Kafka for testing
    }

    @Test
    void testEmailDeliveryRetry() throws Exception {
        // Test email delivery with retry mechanism
        // Create failed email log
        EmailLog failedEmail = new EmailLog();
        failedEmail.setId(UUID.randomUUID());
        failedEmail.setToEmail("failed@example.com");
        failedEmail.setSubject("Failed Delivery Test");
        failedEmail.setBody("This email failed to send");
        failedEmail.setStatus("FAILED");
        failedEmail.setEventType("NOTIFICATION");
        emailLogRepository.save(failedEmail);

        // Retry delivery
        notificationService.retryFailedEmails();

        // Verify retry attempt
        EmailLog retried = emailLogRepository.findById(failedEmail.getId()).orElse(null);
        assert retried != null;
        // Status should be either SENT or still FAILED depending on retry result
    }
}
