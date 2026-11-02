package com.saas.notification.inapp;

import java.time.Instant;
import java.util.UUID;

/** A notification as its owner sees it. */
public record NotificationView(UUID id, String eventType, String title, String message, String level, boolean read,
        Instant createdAt) {
}
