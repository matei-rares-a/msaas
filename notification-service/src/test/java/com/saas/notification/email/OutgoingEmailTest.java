package com.saas.notification.email;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What may reach a provider, and what the code never prints (REQ-NOTIF-06). */
class OutgoingEmailTest {

    private static OutgoingEmail email(String to, String subject, String template) {
        return new OutgoingEmail(UUID.randomUUID(), UUID.randomUUID(), null, template, to, subject, "body with secret-token", Map.of());
    }

    @Test
    @Tag("REQ-NOTIF-08")
    void anOrdinaryEmailIsAccepted() {
        assertThat(email("first.last+tag@example.co.uk", "Hello", "INVITATION").invalidReason()).isNull();
    }

    @ParameterizedTest(name = "address [{0}] is refused")
    @ValueSource(strings = {"", "nobody", "a@b", "a b@example.com", "a@example.com\r\nBcc: x@example.com", "a@example.com,b@example.com",
            "<a@example.com>", "a@example.com;b@example.com", "\"a\"@example.com"})
    @Tag("REQ-NOTIF-08")
    void anAddressThatIsMalformedOrCouldInjectAHeaderIsRefused(String address) {
        assertThat(email(address, "Hello", "INVITATION").invalidReason()).isEqualTo("INVALID_ADDRESS");
    }

    @Test
    @Tag("REQ-NOTIF-08")
    void aMissingOrTooLongAddressIsRefused() {
        assertThat(email(null, "Hello", "INVITATION").invalidReason()).isEqualTo("INVALID_ADDRESS");
        assertThat(email("a".repeat(250) + "@example.com", "Hello", "INVITATION").invalidReason()).isEqualTo("INVALID_ADDRESS");
    }

    @ParameterizedTest(name = "subject [{0}] is refused")
    @ValueSource(strings = {"", "  ", "line one\nBcc: x@example.com", "tab\there"})
    @Tag("REQ-NOTIF-08")
    void aSubjectThatIsBlankOrHasControlCharactersIsRefused(String subject) {
        assertThat(email("a@example.com", subject, "INVITATION").invalidReason()).isEqualTo("INVALID_SUBJECT");
    }

    @Test
    @Tag("REQ-NOTIF-08")
    void aTooLongSubjectAndABadTemplateAreRefused() {
        assertThat(email("a@example.com", "s".repeat(256), "INVITATION").invalidReason()).isEqualTo("INVALID_SUBJECT");
        assertThat(email("a@example.com", "Hello", "not a template").invalidReason()).isEqualTo("INVALID_MESSAGE");
        assertThat(email("a@example.com", "Hello", null).invalidReason()).isEqualTo("INVALID_MESSAGE");
    }

    @Test
    @Tag("REQ-NOTIF-06")
    void noEmailCommandOrMessagePrintsItsAddressOrItsSecrets() {
        UUID id = UUID.randomUUID();
        String secret = "tok-" + UUID.randomUUID();
        String address = "private.person@example.com";

        String printed = String.join(" ",
                new OutgoingEmail(id, id, null, "INVITATION", address, "Hello", "link?token=" + secret, Map.of()).toString(),
                new EmailCommands.Invitation(id, id, address, "USER", secret).toString(),
                new EmailCommands.PasswordReset(id, address, secret).toString(),
                new EmailCommands.PasswordChanged(id, address).toString(),
                new EmailCommands.TenantEmail(id, id, address, "NOTICE", "Hello", "link?token=" + secret).toString());

        assertThat(printed).doesNotContain(address).doesNotContain(secret).doesNotContain("link?token");
    }

    @Test
    @Tag("REQ-NOTIF-06")
    void aTokenIsUrlEncodedIntoTheLinkOfTheEmailsThatCarryOne() {
        EmailTemplates templates = new EmailTemplates("https://app.example.com/", "help@example.com");

        assertThat(templates.invitationBody("MANAGER", "a b&c=d")).contains("https://app.example.com/accept-invitation?token=a+b%26c%3Dd")
                .contains("as MANAGER");
        assertThat(templates.passwordResetBody("t-1")).contains("https://app.example.com/reset-password?token=t-1");
        assertThat(templates.passwordChangedBody()).contains("help@example.com").doesNotContain("token");
    }
}
