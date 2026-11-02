package com.saas.notification.email;

/**
 * Hands one email to whatever actually delivers it (REQ-NOTIF-08). A provider reports a delivery it could
 * not make by throwing {@link EmailDeliveryException}; retrying is not its job, {@link EmailSender} does
 * that. The body can hold a link with a token, so an implementation must never log or keep it.
 */
public interface EmailProvider {

    void send(String to, String subject, String body) throws EmailDeliveryException;
}
