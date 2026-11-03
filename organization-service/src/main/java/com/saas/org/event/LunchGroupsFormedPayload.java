package com.saas.org.event;

import com.saas.shared.events.EventPayload;

import java.util.List;
import java.util.UUID;

/**
 * The groups of a lunch round were formed (REQ-LUNCH-04, REQ-NOTIF-03): the groups as lists of user ids and the week, nothing
 * else - names are read from the service when shown, never copied into an event (REQ-EVT-02). Not an audited action:
 * rounds and enrolments are not in the activity journal (REQ-LUNCH-05).
 */
public record LunchGroupsFormedPayload(UUID tenantId, UUID roundId, String weekStart, List<List<UUID>> groups)
        implements EventPayload {
}
