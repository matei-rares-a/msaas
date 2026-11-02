package com.saas.notification.export;

import com.saas.shared.events.EventPayload;

import java.util.UUID;

/** Command published to RabbitMQ when an export is too big to run inline (REQ-MON-05): identifiers only, the filter stays in the job row. */
public record ActivityExportRequested(UUID jobId, UUID tenantId, UUID requestedBy) implements EventPayload {

    public static final String EVENT_TYPE = "ACTIVITY_EXPORT_REQUESTED";
}
