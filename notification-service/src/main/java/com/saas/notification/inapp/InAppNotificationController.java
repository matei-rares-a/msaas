package com.saas.notification.inapp;

import com.saas.notification.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The caller's own in-app notifications (REQ-NOTIF-01, REQ-NOTIF-04). The user and tenant are the identity the
 * gateway forwarded from the token; nothing in the path, query or body can name another. There is deliberately
 * no way to create a notification here (REQ-NOTIF-02): they come only from the system's events. Any role may
 * read its own, including a read-only member - marking one read changes only the reader's own state.
 * Responses are never cached: the unread count is polled.
 */
@RestController
@RequestMapping("/notifications/in-app")
@Tag(name = "In-app notifications")
public class InAppNotificationController {

    private static final String[] ROLES = {"ADMIN", "MANAGER", "USER"};
    private static final String DENIED = "unknown role";

    private final InAppNotificationService service;

    public InAppNotificationController(InAppNotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "My notifications in the current organization, newest first, with the unread total")
    public ResponseEntity<NotificationPage> list(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return noStore(service.list(caller(userId, tenantId, role), page, size));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many of my notifications are unread (polled every 60 seconds by the app)")
    public ResponseEntity<Map<String, Long>> unreadCount(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role) {
        return noStore(Map.of("unreadCount", service.unreadCount(caller(userId, tenantId, role))));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Mark one of my notifications as read")
    public ResponseEntity<NotificationView> markRead(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role,
            @PathVariable UUID notificationId) {
        return noStore(service.markRead(caller(userId, tenantId, role), notificationId));
    }

    @PatchMapping("/read")
    @Operation(summary = "Mark all my notifications in the current organization as read")
    public ResponseEntity<Map<String, Integer>> markAllRead(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestHeader(value = "X-Role", required = false) String role) {
        return noStore(Map.of("updated", service.markAllRead(caller(userId, tenantId, role))));
    }

    private static Caller caller(String userId, String tenantId, String role) {
        Caller caller = Caller.authenticate(userId, tenantId, role);
        caller.requireRole(DENIED, ROLES);
        return caller;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
