package com.saas.notification.inapp;

import java.util.List;

/** One page of a user's notifications, newest first, with how many are unread in total (REQ-NOTIF-04). */
public record NotificationPage(List<NotificationView> items, int page, int size, long totalItems, long unreadCount) {
}
