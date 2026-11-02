package com.saas.notification.activity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The filters of a journal query, already parsed and validated; every one is optional (REQ-MON-04). */
public record ActivityLogQuery(Instant from, Instant to, List<String> eventTypes, UUID actorUserId) {

    public static final int MAX_EVENT_TYPES = 20;

    public ActivityLogQuery {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }

    public static ActivityLogQuery none() {
        return new ActivityLogQuery(null, null, List.of(), null);
    }
}
