package com.saas.org.lunch;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * When a round closes (REQ-LUNCH-01, REQ-LUNCH-02): the configured weekday at the configured time in the tenant's time
 * zone, in the week - Monday to Sunday - the round belongs to. The round that can be joined now is the one of this week while
 * its cut-off is still ahead, otherwise the one of the next week; at the cut-off itself it is already closed.
 */
public final class LunchSchedule {

    private LunchSchedule() {
    }

    /** The Monday of the week {@code date} is in. */
    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The instant a round closes: {@code day} of the week starting {@code weekStart}, at {@code cutoff} in {@code zone}. */
    public static Instant deadline(LocalDate weekStart, DayOfWeek day, LocalTime cutoff, ZoneId zone) {
        LocalDate date = weekStart.plusDays(day.getValue() - 1L);
        return ZonedDateTime.of(date, cutoff, zone).toInstant();
    }

    /** The Monday of the round that can be joined at {@code now}. */
    public static LocalDate openWeek(Instant now, DayOfWeek day, LocalTime cutoff, ZoneId zone) {
        LocalDate monday = weekStart(now.atZone(zone).toLocalDate());
        return now.isBefore(deadline(monday, day, cutoff, zone)) ? monday : monday.plusWeeks(1);
    }
}
