package com.saas.org.leave;

import java.util.List;

public record LeavePage(List<LeaveRequestView> items, int page, int size, long totalItems) {
}
