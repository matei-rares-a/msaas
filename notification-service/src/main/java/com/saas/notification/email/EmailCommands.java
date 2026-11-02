package com.saas.notification.email;

import java.util.UUID;

/**
 * The payloads of the email commands other services publish to RabbitMQ (REQ-EVT-01): the field names are
 * the contract with the producers' command classes (organization-service {@code SendInvitationEmailCommand},
 * auth-service {@code SendPasswordResetEmailCommand} / {@code SendPasswordChangedConfirmationCommand}).
 * They carry an address and tokens, so none of them prints its fields.
 */
final class EmailCommands {

    static final String SEND_INVITATION_EMAIL = "SEND_INVITATION_EMAIL";
    static final String SEND_PASSWORD_RESET_EMAIL = "SEND_PASSWORD_RESET_EMAIL";
    static final String SEND_PASSWORD_CHANGED_CONFIRMATION = "SEND_PASSWORD_CHANGED_CONFIRMATION";
    static final String SEND_TENANT_EMAIL = "SEND_TENANT_EMAIL";

    private EmailCommands() {
    }

    record Invitation(UUID invitationId, UUID tenantId, String email, String role, String token) {
        @Override
        public String toString() {
            return "Invitation[invitationId=" + invitationId + "]";
        }
    }

    record PasswordReset(UUID userId, String email, String resetToken) {
        @Override
        public String toString() {
            return "PasswordReset[userId=" + userId + "]";
        }
    }

    record PasswordChanged(UUID userId, String email) {
        @Override
        public String toString() {
            return "PasswordChanged[userId=" + userId + "]";
        }
    }

    /**
     * A tenant-related email to a member that has no template of its own (billing notices, for one): the
     * sender names the template id and writes subject and body, and says which member it is for so the log
     * can be anonymized at offboarding.
     */
    record TenantEmail(UUID tenantId, UUID recipientUserId, String email, String template, String subject, String body) {
        @Override
        public String toString() {
            return "TenantEmail[template=" + template + "]";
        }
    }
}
