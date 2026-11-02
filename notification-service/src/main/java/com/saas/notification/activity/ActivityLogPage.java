package com.saas.notification.activity;

import java.util.List;

/** One page of a journal query (REQ-MON-04). */
public record ActivityLogPage(List<ActivityLogRecord> items, int page, int size, long totalItems) {

    public int totalPages() {
        return size == 0 ? 0 : (int) ((totalItems + size - 1) / size);
    }
}
