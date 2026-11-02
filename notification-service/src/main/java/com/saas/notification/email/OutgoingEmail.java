package com.saas.notification.email;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One email to deliver, already turned from a command into its final form. {@code tenantId} is null for an
 * identity-level email (password reset or confirmation): those belong to no tenant, so nothing about them is
 * ever recorded in one (REQ-PWD-08). {@code recipientUserId} is set when the recipient is a member, so
 * offboarding can find and anonymize the address by id (REQ-DATA-01). {@code reference} holds identifiers
 * that go into the journal entry (for instance the invitation id), never an address. The body holds secrets
 * (links with tokens), so it is left out of {@link #toString()}.
 */
record OutgoingEmail(UUID commandId, UUID tenantId, UUID recipientUserId, String template, String to,
        String subject, String body, Map<String, Object> reference) {

    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@<>,;\"'()\\\\]+@[^\\s@<>,;\"'()\\\\]+\\.[^\\s@<>,;\"'()\\\\]+$");
    private static final Pattern TEMPLATE = Pattern.compile("^[A-Z0-9_]{1,50}$");

    static final int MAX_ADDRESS = 254;
    static final int MAX_SUBJECT = 255;

    /** @return why this email must not be handed to a provider (a header-injection or malformed value), or null when it is fine */
    String invalidReason() {
        if (to == null || to.length() > MAX_ADDRESS || !ADDRESS.matcher(to).matches()) {
            return "INVALID_ADDRESS";
        }
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT || subject.chars().anyMatch(Character::isISOControl)) {
            return "INVALID_SUBJECT";
        }
        if (body == null || template == null || !TEMPLATE.matcher(template).matches()) {
            return "INVALID_MESSAGE";
        }
        return null;
    }

    @Override
    public String toString() {
        return "OutgoingEmail[commandId=" + commandId + ", tenantId=" + tenantId + ", template=" + template + "]";
    }
}
