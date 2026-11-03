package com.saas.org.lunch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** When a round closes and which week can be joined, in the tenant's time zone (REQ-LUNCH-01, REQ-LUNCH-02, REQ-MOD-01). */
@Tag("REQ-LUNCH-02")
class LunchScheduleTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime ELEVEN = LocalTime.of(11, 0);
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    @Test
    void aWeekStartsOnMondayWhateverDayOfTheWeekYouAskFrom() {
        for (int i = 0; i < 7; i++) {
            assertThat(LunchSchedule.weekStart(MONDAY.plusDays(i))).isEqualTo(MONDAY);
        }
        assertThat(LunchSchedule.weekStart(MONDAY.plusDays(7))).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.weekStart(LocalDate.of(2027, 1, 1))).as("across a year boundary").isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void theCutoffIsTheConfiguredWeekdayAndTimeInTheTenantsZone() {
        // Thursday 2026-03-05 11:00 in Bucharest (UTC+2 in March, before the clock change)
        assertThat(LunchSchedule.deadline(MONDAY, DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).isEqualTo(Instant.parse("2026-03-05T09:00:00Z"));
        assertThat(LunchSchedule.deadline(MONDAY, DayOfWeek.THURSDAY, ELEVEN, NEW_YORK)).isEqualTo(Instant.parse("2026-03-05T16:00:00Z"));
        assertThat(LunchSchedule.deadline(MONDAY, DayOfWeek.MONDAY, LocalTime.of(0, 0), BUCHAREST)).as("first minute of the week").isEqualTo(Instant.parse("2026-03-01T22:00:00Z"));
        assertThat(LunchSchedule.deadline(MONDAY, DayOfWeek.SUNDAY, LocalTime.of(23, 59), BUCHAREST)).isEqualTo(Instant.parse("2026-03-08T21:59:00Z"));
    }

    @Test
    void theCutoffFollowsDaylightSavingTime() {
        LocalDate beforeChange = LocalDate.of(2026, 3, 23); // Bucharest moves to UTC+3 on Sunday 2026-03-29
        LocalDate afterChange = LocalDate.of(2026, 3, 30);

        assertThat(LunchSchedule.deadline(beforeChange, DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).isEqualTo(Instant.parse("2026-03-26T09:00:00Z"));
        assertThat(LunchSchedule.deadline(afterChange, DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).isEqualTo(Instant.parse("2026-04-02T08:00:00Z"));
    }

    @Test
    void thisWeeksRoundIsOpenWhileItsCutoffIsAheadAndNextWeeksOnceItHasPassed() {
        Instant cutoff = Instant.parse("2026-03-05T09:00:00Z");

        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-02T08:00:00Z"), DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).as("Monday morning").isEqualTo(MONDAY);
        assertThat(LunchSchedule.openWeek(cutoff.minusSeconds(1), DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).as("a second before").isEqualTo(MONDAY);
        assertThat(LunchSchedule.openWeek(cutoff, DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).as("at the cut-off it is closed").isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-06T10:00:00Z"), DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).as("Friday").isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-08T20:00:00Z"), DayOfWeek.THURSDAY, ELEVEN, BUCHAREST)).as("Sunday evening").isEqualTo(MONDAY.plusWeeks(1));
    }

    @Test
    void theWeekIsTheTenantsNotUtcsSoSundayNightInUtcCanAlreadyBeMondayAtTheTenant() {
        Instant sundayNightUtc = Instant.parse("2026-03-08T22:30:00Z"); // Monday 00:30 in Bucharest, Sunday 17:30 in New York

        assertThat(LunchSchedule.openWeek(sundayNightUtc, DayOfWeek.WEDNESDAY, ELEVEN, BUCHAREST)).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.openWeek(sundayNightUtc, DayOfWeek.WEDNESDAY, ELEVEN, NEW_YORK)).as("New York is still in the old week, whose cut-off is over").isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-08T22:30:00Z"), DayOfWeek.SUNDAY, LocalTime.of(23, 0), NEW_YORK))
                .as("a Sunday cut-off in New York is still ahead").isEqualTo(MONDAY);
    }

    @Test
    void aCutoffEarlierInTheWeekThanTodayMeansNextWeeksRound() {
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-04T10:00:00Z"), DayOfWeek.TUESDAY, ELEVEN, BUCHAREST)).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-02T08:00:00Z"), DayOfWeek.MONDAY, ELEVEN, BUCHAREST)).as("Monday 10:00, cut-off 11:00").isEqualTo(MONDAY);
        assertThat(LunchSchedule.openWeek(Instant.parse("2026-03-02T09:00:00Z"), DayOfWeek.MONDAY, ELEVEN, BUCHAREST)).as("Monday 11:00").isEqualTo(MONDAY.plusWeeks(1));
    }
}
