package com.saas.org.leave;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One approved leave on the team calendar, which every member sees: who and when, and nothing else - no type, no reason, no
 * status, no days (REQ-LEAVE-06). The service keeps no names, so the person is the user id.
 */
public record CalendarEntry(UUID userId, LocalDate startDate, LocalDate endDate) {
}
