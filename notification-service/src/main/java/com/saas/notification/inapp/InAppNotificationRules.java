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
 *   <li>{@code TRIAL_EXPIRING} - to the admins and managers;</li>
 *   <li>{@code LEAVE_REQUESTED} - to the other approvers, whose ids are in the event (not the requester);</li>
 *   <li>{@code LEAVE_APPROVED} and {@code LEAVE_REJECTED} - to the requester;</li>
 *   <li>{@code LEAVE_CANCELLED} - to the requester, when an approver cancelled their approved leave (not when they did);</li>
 *   <li>{@code LUNCH_GROUPS_FORMED} - to every member of every group, telling them how big their group is (the names are read
 *       from the service when the group is shown, never copied into a notification);</li>
 *   <li>{@code LUNCH_ROUND_CANCELLED} - to the participants named in the event.</li>
 * </ul>
 */
public class InAppNotificationRules {

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARNING = "WARNING";

    private static final Set<String> APPROVER_ROLES = Set.of("ADMIN", "MANAGER");
    private static final Pattern ROLE = Pattern.compile("^[A-Z_]{1,30}$");

    public static final Set<String> HANDLED_EVENT_TYPES = Set.of("MEMBER_ROLE_CHANGED", "ADMIN_DEMOTION_REQUESTED",
            "INVITATION_ACCEPTED", "SUBSCRIPTION_CHANGED", "TRIAL_EXPIRING", "LEAVE_REQUESTED", "LEAVE_APPROVED", "LEAVE_REJECTED",
            "LEAVE_CANCELLED", "LUNCH_GROUPS_FORMED", "LUNCH_ROUND_CANCELLED");

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
            case "LEAVE_REQUESTED" -> leaveRequested(payload);
            case "LEAVE_APPROVED" -> leaveDecision(payload, "approved", "Leave request approved", LEVEL_INFO);
            case "LEAVE_REJECTED" -> leaveDecision(payload, "rejected", "Leave request rejected", LEVEL_WARNING);
            case "LEAVE_CANCELLED" -> leaveCancelled(payload);
            case "LUNCH_GROUPS_FORMED" -> lunchGroupsFormed(payload);
            case "LUNCH_ROUND_CANCELLED" -> lunchRoundCancelled(payload);
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

    private List<NewNotification> leaveRequested(JsonNode payload) {
        UUID requester = uuid(payload, "requesterUserId");
        JsonNode approvers = payload.get("approverUserIds");
        if (approvers == null || !approvers.isArray()) {
            throw new IllegalArgumentException("the event has no approverUserIds");
        }
        String message = "A leave request for " + dates(payload) + " is waiting for a decision.";
        Set<UUID> recipients = new LinkedHashSet<>();
        for (JsonNode approver : approvers) {
            recipients.add(id(approver, "approverUserIds"));
        }
        recipients.remove(requester);
        List<NewNotification> notifications = new ArrayList<>();
        for (UUID recipient : recipients) {
            notifications.add(new NewNotification(recipient, "LEAVE_REQUESTED", "New leave request", message, LEVEL_INFO));
        }
        return notifications;
    }

    private List<NewNotification> leaveDecision(JsonNode payload, String outcome, String title, String level) {
        return List.of(new NewNotification(uuid(payload, "requesterUserId"), eventTypeOf(outcome), title,
                "Your leave request for " + dates(payload) + " was " + outcome + ".", level));
    }

    private List<NewNotification> leaveCancelled(JsonNode payload) {
        UUID requester = uuid(payload, "requesterUserId");
        if (requester.equals(optionalUuid(payload, "actorUserId"))) {
            return List.of(); // cancelling your own leave is not news to you
        }
        return List.of(new NewNotification(requester, "LEAVE_CANCELLED", "Leave cancelled",
                "Your approved leave for " + dates(payload) + " was cancelled by an approver.", LEVEL_WARNING));
    }

    private List<NewNotification> lunchGroupsFormed(JsonNode payload) {
        String week = date(payload, "weekStart");
        JsonNode groups = payload.get("groups");
        if (groups == null || !groups.isArray()) {
            throw new IllegalArgumentException("the event has no groups");
        }
        Set<UUID> told = new LinkedHashSet<>();
        List<NewNotification> notifications = new ArrayList<>();
        for (JsonNode group : groups) {
            if (!group.isArray()) {
                throw new IllegalArgumentException("a group is not a list of ids");
            }
            for (JsonNode member : group) {
                UUID user = id(member, "groups");
                if (told.add(user)) {
                    notifications.add(new NewNotification(user, "LUNCH_GROUPS_FORMED", "Your lunch group is ready",
                            "Your lunch group for the week of " + week + " is ready: you will have lunch with " + (group.size() - 1)
                                    + (group.size() == 2 ? " other person" : " other people") + ". Open the lunch page to see who.", LEVEL_INFO));
                }
            }
        }
        return notifications;
    }

    private List<NewNotification> lunchRoundCancelled(JsonNode payload) {
        String week = date(payload, "weekStart");
        JsonNode participants = payload.get("participantUserIds");
        if (participants == null || !participants.isArray()) {
            throw new IllegalArgumentException("the event has no participantUserIds");
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (JsonNode participant : participants) {
            recipients.add(id(participant, "participantUserIds"));
        }
        List<NewNotification> notifications = new ArrayList<>();
        for (UUID recipient : recipients) {
            notifications.add(new NewNotification(recipient, "LUNCH_ROUND_CANCELLED", "Lunch round cancelled",
                    "The lunch round for the week of " + week + " was cancelled.", LEVEL_WARNING));
        }
        return notifications;
    }

    private static String eventTypeOf(String outcome) {
        return "approved".equals(outcome) ? "LEAVE_APPROVED" : "LEAVE_REJECTED";
    }

    /** "2026-12-01 to 2026-12-05"; the dates must look like dates, so nothing else a producer sends is echoed. */
    private static String dates(JsonNode payload) {
        return date(payload, "startDate") + " to " + date(payload, "endDate");
    }

    private static String date(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        try {
            return java.time.LocalDate.parse(value).toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " is not a date");
        }
    }

    private static UUID id(JsonNode node, String field) {
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " holds something that is not an id");
        }
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
