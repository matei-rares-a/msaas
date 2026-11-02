package com.saas.notification.email;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The text of the emails this service writes itself. Links point at the web application
 * ({@code saas.email.app-base-url}); a token is URL-encoded into the link and is the only place it appears.
 */
class EmailTemplates {

    static final String INVITATION = "INVITATION";
    static final String PASSWORD_RESET = "PASSWORD_RESET";
    static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    private final String appBaseUrl;
    private final String supportContact;

    EmailTemplates(String appBaseUrl, String supportContact) {
        this.appBaseUrl = appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
        this.supportContact = supportContact;
    }

    String invitationSubject() {
        return "You have been invited to join an organization";
    }

    String invitationBody(String role, String token) {
        return "You have been invited to join an organization as " + role + ".\n\n"
                + "Accept the invitation: " + appBaseUrl + "/accept-invitation?token=" + encode(token) + "\n\n"
                + "If you were not expecting this invitation, you can ignore this email.\n";
    }

    String passwordResetSubject() {
        return "Reset your password";
    }

    String passwordResetBody(String token) {
        return "We received a request to reset your password.\n\n"
                + "Choose a new password: " + appBaseUrl + "/reset-password?token=" + encode(token) + "\n\n"
                + "The link works once and expires soon. If you did not ask for this, you can ignore this email.\n";
    }

    String passwordChangedSubject() {
        return "Your password was changed";
    }

    String passwordChangedBody() {
        return "The password of your account was just changed and all your sessions were signed out.\n\n"
                + "If you did not do this, contact us right away: " + supportContact + "\n";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
