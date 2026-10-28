package com.saas.org.dto;

import com.saas.org.entity.Invitation;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code status} is derived at read time from {@code expiresAt} vs. the injected {@code Clock}, not
 * echoed from the stored row: a PENDING-but-expired invitation must display as {@code "EXPIRED"} (no
 * such value is ever persisted - see V6's migration comment), otherwise it would appear forever as
 * "PENDING" to an ADMIN browsing the list.
 */
public record InvitationResponse(UUID id, String email, String role, String status, Instant expiresAt) {

    public static InvitationResponse of(Invitation invitation, Instant now) {
        String displayStatus = invitation.getStatus() == Invitation.Status.PENDING && invitation.getExpiresAt().isBefore(now)
                ? "EXPIRED"
                : invitation.getStatus().name();
        return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole().name(),
                displayStatus, invitation.getExpiresAt());
    }
}
