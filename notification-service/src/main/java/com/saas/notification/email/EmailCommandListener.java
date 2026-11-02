package com.saas.notification.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.Map;
import java.util.UUID;

/**
 * Consumer of the email commands (REQ-EVT-01: commands travel over RabbitMQ, where each one is handed to a
 * single consumer and removed once handled). It turns a command into an {@link OutgoingEmail} and gives it to
 * {@link EmailDeliveryService}. A command it cannot parse, or that fails for a reason other than the provider
 * (the database, say), is retried by the container and then dead-lettered (REQ-EVT-07); a provider that does
 * not deliver is not such a failure - it ends as a {@code FAILED} email and the command is done.
 *
 * <p>The envelope's {@code tenantId} is the only source of the tenant. The password commands are identity-level
 * whatever the envelope says, so a password email can never be recorded in a tenant.
 */
public class EmailCommandListener {

    private static final Logger LOG = LoggerFactory.getLogger(EmailCommandListener.class);

    private final EmailDeliveryService deliveryService;
    private final EmailTemplates templates;
    private final ObjectMapper objectMapper;

    EmailCommandListener(EmailDeliveryService deliveryService, EmailTemplates templates, ObjectMapper objectMapper) {
        this.deliveryService = deliveryService;
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = EmailConfig.QUEUE, containerFactory = "emailListenerContainerFactory")
    public void onCommand(String message) throws JsonProcessingException {
        JsonNode envelope = objectMapper.readTree(message);
        String type = envelope.path("eventType").asText(null);
        if (type == null) {
            throw new IllegalArgumentException("the command has no eventType");
        }
        UUID commandId = UUID.fromString(envelope.path("eventId").asText());
        UUID tenantId = envelope.hasNonNull("tenantId") ? UUID.fromString(envelope.get("tenantId").asText()) : null;
        JsonNode payload = envelope.path("payload");

        OutgoingEmail email = switch (type) {
            case EmailCommands.SEND_INVITATION_EMAIL -> invitation(commandId, requireTenant(tenantId, type), payload);
            case EmailCommands.SEND_PASSWORD_RESET_EMAIL -> passwordReset(commandId, payload);
            case EmailCommands.SEND_PASSWORD_CHANGED_CONFIRMATION -> passwordChanged(commandId, payload);
            case EmailCommands.SEND_TENANT_EMAIL -> tenantEmail(commandId, requireTenant(tenantId, type), payload);
            default -> null;
        };
        if (email == null) {
            LOG.info("Ignoring a command of type {} (not an email command)", type.replaceAll("[^A-Za-z0-9_.-]", "?"));
            return;
        }
        deliveryService.deliver(email);
    }

    private OutgoingEmail invitation(UUID commandId, UUID tenantId, JsonNode payload) throws JsonProcessingException {
        EmailCommands.Invitation command = objectMapper.treeToValue(payload, EmailCommands.Invitation.class);
        return new OutgoingEmail(commandId, tenantId, null, EmailTemplates.INVITATION, command.email(),
                templates.invitationSubject(), templates.invitationBody(command.role(), command.token()),
                command.invitationId() == null ? Map.of() : Map.of("invitationId", command.invitationId()));
    }

    private OutgoingEmail passwordReset(UUID commandId, JsonNode payload) throws JsonProcessingException {
        EmailCommands.PasswordReset command = objectMapper.treeToValue(payload, EmailCommands.PasswordReset.class);
        return new OutgoingEmail(commandId, null, command.userId(), EmailTemplates.PASSWORD_RESET, command.email(),
                templates.passwordResetSubject(), templates.passwordResetBody(command.resetToken()), Map.of());
    }

    private OutgoingEmail passwordChanged(UUID commandId, JsonNode payload) throws JsonProcessingException {
        EmailCommands.PasswordChanged command = objectMapper.treeToValue(payload, EmailCommands.PasswordChanged.class);
        return new OutgoingEmail(commandId, null, command.userId(), EmailTemplates.PASSWORD_CHANGED, command.email(),
                templates.passwordChangedSubject(), templates.passwordChangedBody(), Map.of());
    }

    private OutgoingEmail tenantEmail(UUID commandId, UUID tenantId, JsonNode payload) throws JsonProcessingException {
        EmailCommands.TenantEmail command = objectMapper.treeToValue(payload, EmailCommands.TenantEmail.class);
        return new OutgoingEmail(commandId, tenantId, command.recipientUserId(), command.template(), command.email(),
                command.subject(), command.body(), Map.of());
    }

    private static UUID requireTenant(UUID tenantId, String type) {
        if (tenantId == null) {
            throw new IllegalArgumentException("a " + type + " command must name a tenant");
        }
        return tenantId;
    }
}
