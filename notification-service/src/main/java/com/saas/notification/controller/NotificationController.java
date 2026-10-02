package com.saas.notification.controller;

import com.saas.notification.entity.InAppNotification;
import com.saas.notification.repository.InAppNotificationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final InAppNotificationRepository inAppNotificationRepository;

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Notification service is running");
    }

    @GetMapping("/in-app")
    @Operation(summary = "List in-app notifications for current user")
    public ResponseEntity<List<InAppNotification>> getInAppNotifications(
            @RequestHeader("X-Org-Id") String orgId,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(
                inAppNotificationRepository.findAllByOrganizationIdAndUserIdOrderByCreatedAtDesc(
                        UUID.fromString(orgId),
                        UUID.fromString(userId)
                )
        );
    }

    @PatchMapping("/in-app/{id}/read")
    @Operation(summary = "Mark in-app notification as read")
    public ResponseEntity<Void> markAsRead(@PathVariable UUID id) {
        InAppNotification notification = inAppNotificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Notification not found"));
        notification.setRead(true);
        inAppNotificationRepository.save(notification);
        return ResponseEntity.ok().build();
    }
}
