package com.saas.notification.controller;

import com.saas.notification.dto.CreateInAppNotificationRequest;
import com.saas.notification.entity.InAppNotification;
import com.saas.notification.repository.InAppNotificationRepository;
import com.saas.notification.service.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications")
public class NotificationController {

    private final InAppNotificationRepository inAppNotificationRepository;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Notification service is running");
    }

    @GetMapping("/feature-flags")
    @Operation(summary = "Get feature flags")
    public ResponseEntity<Map<String, Boolean>> getFeatureFlags() {
        return ResponseEntity.ok(featureFlagService.getAll());
    }

    @PatchMapping("/feature-flags/{key}")
    @Operation(summary = "Update feature flag")
    public ResponseEntity<Map<String, Boolean>> updateFeatureFlag(
            @PathVariable String key,
            @RequestParam boolean enabled) {
        return ResponseEntity.ok(featureFlagService.update(key, enabled));
    }

    @PostMapping("/in-app")
    @Operation(summary = "Create in-app notification")
    public ResponseEntity<InAppNotification> createInAppNotification(
            @RequestHeader("X-Org-Id") String orgId,
            @Valid @RequestBody CreateInAppNotificationRequest request) {
        InAppNotification notification = new InAppNotification();
        notification.setId(UUID.randomUUID());
        notification.setOrganizationId(UUID.fromString(orgId));
        notification.setUserId(request.getUserId());
        notification.setTitle(request.getTitle());
        notification.setMessage(request.getMessage());
        notification.setLevel(request.getLevel());
        notification.setRead(false);
        return ResponseEntity.status(HttpStatus.CREATED).body(inAppNotificationRepository.save(notification));
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
