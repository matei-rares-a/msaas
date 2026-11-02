package com.saas.notification.inapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saas.notification.inapp.InAppNotificationService.NewNotification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Which events create a notification, for whom, and what it says (REQ-NOTIF-03, the v1 list of events that exist
 * so far; the leave and lunch-roulette events are added with their modules). One place, so the list is easy to
 * check against the requirement. A notification never contains a name or an email, only roles, dates and
 * what happened (REQ-EVT-02): the app shows who, by id, where it can resolve the name.
 *
 * <ul>
 *   <li>{@code MEMBER_ROLE_CHANGED} - to the user whose role changed (not when they changed it themselves);</li>
 *   <li>{@code ADMIN_DEMOTION_REQUESTED} - to the admin asked to accept being demoted;</li>
 *   <li>{@code INVITATION_ACCEPTED} - to the admins and managers, except the user who just joined;</li>
 *   <li>{@code SUBSCRIPTION_CHANGED} to PAST_DUE - to the admins and managers, only when it was not already PAST_DUE;</li>
 *   <li>{@code TRIAL_EXPIRING} - to the admins and managers.</li>
 * </ul>
 */
public class InAppNotificationRules {

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARNING = "WARNING";

    private static final Set<String> APPROVER_ROLES = Set.of("ADMIN", "MANAGER");
    private static final Pattern ROLE = Pattern.compile("^[A-Z_]{1,30}$");

    public static final Set<String> HANDLED_EVENT_TYPES = Set.of("MEMBER_ROLE_CHANGED", "ADMIN_DEMOTION_REQUESTED",
            "INVITATION_ACCEPTED", "SUBSCRIPTION_CHANGED", "TRIAL_EXPIRING");

    private final MemberDirectory directory;
    private final ObjectMapper objectMapper;

    public InAppNotificationRules(MemberDirectory directory, ObjectMapper objectMapper) {
        this.directory = directory;
        this.objectMapper = objectMapper;
    }

    /** @throws IllegalArgumentException when the event lacks a field it must have */
    public List<NewNotification> notificationsFor(String eventType, UUID tenantId, JsonNode payload) {
        return switch (eventType) {
            case "MEMBER_ROLE_CHANGED" -> roleChanged(payload);
            case "ADMIN_DEMOTION_REQUESTED" -> demotionRequested(payload);
            case "INVITATION_ACCEPTED" -> invitationAccepted(tenantId, payload);
            case "SUBSCRIPTION_CHANGED" -> subscriptionChanged(tenantId, payload);
            case "TRIAL_EXPIRING" -> trialExpiring(tenantId, payload);
            default -> List.of();
        };
    }

    private List<NewNotification> roleChanged(JsonNode payload) {
        UUID target = uuid(payload, "targetUserId");
        UUID actor = optionalUuid(payload, "actorUserId");
        if (target.equals(actor)) {
            return List.of();
        }
        return List.of(new NewNotification(target, "MEMBER_ROLE_CHANGED", "Your role changed",
                "Your role in this organization changed from " + role(payload, "previousRole") + " to " + role(payload, "newRole") + ".",
                LEVEL_INFO));
    }

    private List<NewNotification> demotionRequested(JsonNode payload) {
        return List.of(new NewNotification(uuid(payload, "targetUserId"), "ADMIN_DEMOTION_REQUESTED", "Your answer is needed",
                "An administrator asked to change your role to " + role(payload, "newRole")
                        + ". Accept or reject the request in the members page.", LEVEL_WARNING));
    }

    private List<NewNotification> invitationAccepted(UUID tenantId, JsonNode payload) {
        UUID joined = uuid(payload, "userId");
        String message = "A new member joined the organization as " + role(payload, "role") + ".";
        return forApprovers(tenantId, joined, "INVITATION_ACCEPTED", "Invitation accepted", message, LEVEL_INFO);
    }

    private List<NewNotification> subscriptionChanged(UUID tenantId, JsonNode payload) {
        if (!"PAST_DUE".equals(payload.path("status").asText(null)) || "PAST_DUE".equals(payload.path("previousStatus").asText(null))) {
            return List.of();
        }
        return forApprovers(tenantId, null, "SUBSCRIPTION_PAST_DUE", "Subscription payment failed",
                "The subscription payment failed. The organization is read-only until a payment succeeds, "
                        + "and is suspended if that takes longer than the grace period.", LEVEL_WARNING);
    }

    private List<NewNotification> trialExpiring(UUID tenantId, JsonNode payload) {
        Instant endsAt = instant(payload, "trialEndsAt");
        return forApprovers(tenantId, null, "TRIAL_EXPIRING", "Your trial is ending soon",
                "The trial of this organization ends on " + endsAt.atZone(ZoneOffset.UTC).toLocalDate()
                        + ". Choose a plan to keep using the service.", LEVEL_WARNING);
    }

    private List<NewNotification> forApprovers(UUID tenantId, UUID except, String eventType, String title, String message, String level) {
        Set<UUID> recipients = new LinkedHashSet<>(directory.memberIdsWithRoles(tenantId, APPROVER_ROLES));
        recipients.remove(except);
        List<NewNotification> notifications = new ArrayList<>();
        for (UUID recipient : recipients) {
            notifications.add(new NewNotification(recipient, eventType, title, message, level));
        }
        return notifications;
    }

    private static UUID uuid(JsonNode payload, String field) {
        UUID value = optionalUuid(payload, field);
        if (value == null) {
            throw new IllegalArgumentException("the event has no " + field);
        }
        return value;
    }

    private static UUID optionalUuid(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " is not an id");
        }
    }

    /** Only a role name that looks like one is put into text; anything else is not echoed. */
    private static String role(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value != null && ROLE.matcher(value).matches() ? value : "another role";
    }

    private Instant instant(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("the event has no " + field);
        }
        try {
            return objectMapper.treeToValue(node, Instant.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(field + " is not a time");
        }
    }
}
