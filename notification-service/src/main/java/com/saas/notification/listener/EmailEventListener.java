package com.saas.notification.listener;

import com.saas.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailEventListener {

    private final NotificationService notificationService;

    @RabbitListener(queues = "email-queue")
    public void handleEmailEvent(Map<String, Object> event) {
        try {
            String eventType = (String) event.get("eventType");
            String email = (String) event.get("email");

            if ("USER_INVITED".equals(eventType)) {
                notificationService.sendInvitationEmail(
                        email,
                        (String) event.get("organizationName"),
                        (String) event.get("inviteToken")
                );
            }
            log.info("Email delivered: {} for {}", eventType, email);
        } catch (Exception ex) {
            log.error("Error processing email event", ex);
        }
    }
}
