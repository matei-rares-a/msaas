package com.saas.notification.listener;

import com.saas.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventListener {

    private final NotificationService notificationService;

    @KafkaListener(topics = "notification-events", groupId = "notif-service")
    public void handleNotificationEvent(Map<String, Object> event) {
        try {
            String eventType = (String) event.get("eventType");

            if ("PASSWORD_RESET".equals(eventType)) {
                notificationService.sendPasswordResetEmail(
                        (String) event.get("email"),
                        (String) event.get("token")
                );
            }
            log.info("Notification processed: {}", eventType);
        } catch (Exception ex) {
            log.error("Error processing kafka event", ex);
        }
    }
}
