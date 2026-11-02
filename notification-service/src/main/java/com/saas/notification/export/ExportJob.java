package com.saas.notification.export;

import java.time.Instant;
import java.util.UUID;

/** An export job as the requester sees it: no file content. */
public record ExportJob(UUID id, UUID requestedBy, Status status, Long rowCount,
        Instant createdAt, Instant completedAt, Instant expiresAt) {

    public enum Status { PENDING, READY }
}
