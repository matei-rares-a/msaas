package com.saas.org.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Counts the days of an interval that count as leave days: no Saturday, no Sunday, no holiday (REQ-LEAVE-02). */
public final class WorkingDays {

    private WorkingDays() {
    }

    /** @param start first day, inclusive; @param end last day, inclusive; @return 0 when no day in the interval counts */
    public static int count(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DayOfWeek weekday = day.getDayOfWeek();
            if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && !holidays.contains(day)) {
                days++;
            }
        }
        return days;
    }
}
