package com.saas.notification.email;

/** A delivery attempt that failed; {@code reason} is a short code (upper-case letters, digits, underscore) safe to store and show. */
public class EmailDeliveryException extends Exception {

    private final String reason;

    public EmailDeliveryException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public EmailDeliveryException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
