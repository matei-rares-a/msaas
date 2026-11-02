package com.saas.notification.activity;

import java.time.Instant;
import java.util.UUID;

/** One journal entry as read back; {@code metadataJson} is the stored JSON object text. */
public record ActivityLogRecord(UUID id, String eventType, UUID actorUserId, Instant createdAt, String metadataJson) {
}
