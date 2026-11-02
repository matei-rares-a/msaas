package com.saas.shared.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The sensitive actions the tenant activity journal records (REQ-MON-01), and where to find who did it
 * and what it was about in each event's payload. One list, shared by the services that publish these
 * events and by the one that records them: the journal writes only the event types named here (an
 * allowlist, so a new event type never reaches the journal by accident - password events, for one, can
 * never get in, REQ-PWD-08) and copies only the metadata fields named here (identifiers, roles and
 * statuses - never a name or an email, REQ-EVT-02). Adding an audited action means adding a line here
 * and publishing the event; the producer tests check that each payload really carries these fields.
 */
public final class ActivityEventCatalog {

    /**
     * @param actorField     payload field holding the acting user's id, or {@code null} when the system acts
     * @param metadataFields payload fields copied into the journal entry's metadata (absent/null ones are skipped)
     */
    public record Entry(String actorField, List<String> metadataFields) {
    }

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        ENTRIES.put("LOGIN", new Entry("userId", List.of("role")));
        ENTRIES.put("MEMBER_ROLE_CHANGED", new Entry("actorUserId", List.of("targetUserId", "previousRole", "newRole")));
        ENTRIES.put("ADMIN_DEMOTION_REQUESTED", new Entry("actorUserId", List.of("requestId", "targetUserId", "newRole")));
        ENTRIES.put("ADMIN_DEMOTION_ACCEPTED", new Entry("actorUserId", List.of("requestId", "newRole")));
        ENTRIES.put("ADMIN_DEMOTION_REJECTED", new Entry("actorUserId", List.of("requestId", "newRole")));
        ENTRIES.put("ADMIN_DEMOTION_ESCALATED", new Entry(null, List.of("requestId", "targetUserId", "requestedBy")));
        ENTRIES.put("INVITATION_CREATED", new Entry("actorUserId", List.of("invitationId", "role")));
        ENTRIES.put("INVITATION_REVOKED", new Entry("actorUserId", List.of("invitationId")));
        ENTRIES.put("INVITATION_ACCEPTED", new Entry("userId", List.of("invitationId", "role")));
        ENTRIES.put("SUBSCRIPTION_CHANGED", new Entry("actorUserId", List.of("planId", "maxUsers", "status")));
        ENTRIES.put("THEME_CHANGED", new Entry("actorUserId", List.of("paletteId")));
        ENTRIES.put("LOGO_CHANGED", new Entry("actorUserId", List.of()));
    }

    private ActivityEventCatalog() {
    }

    public static Optional<Entry> find(String eventType) {
        return Optional.ofNullable(eventType).map(ENTRIES::get);
    }

    public static Set<String> eventTypes() {
        return Set.copyOf(ENTRIES.keySet());
    }
}
