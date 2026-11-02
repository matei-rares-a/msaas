package com.saas.notification.inapp;

/** No such notification for this user in this tenant - which also covers someone else's (REQ-NOTIF-01). */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("no such notification");
    }
}
