package com.saas.org.leave;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What counts as a leave day: no Saturday, no Sunday, no day the tenant listed as a holiday (REQ-LEAVE-02). */
@Tag("REQ-LEAVE-02")
class WorkingDaysTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

    @Test
    void aWorkingWeekIsFiveDays() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(4), Set.of())).isEqualTo(5);
    }

    @Test
    void aFullWeekIncludingTheWeekendIsStillFiveDays() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(6), Set.of())).isEqualTo(5);
    }

    @Test
    void twoWeeksAreTenDaysAndTheWeekendInTheMiddleIsSkipped() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(11), Set.of())).isEqualTo(10);
        assertThat(WorkingDays.count(MONDAY.plusDays(4), MONDAY.plusDays(7), Set.of())).as("Friday to Monday").isEqualTo(2);
    }

    @Test
    void aSingleWorkingDayIsOneAndFirstAndLastDayAreInclusive() {
        assertThat(WorkingDays.count(MONDAY, MONDAY, Set.of())).isEqualTo(1);
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(1), Set.of())).isEqualTo(2);
    }

    @Test
    void aWeekendAloneHasNoWorkingDay() {
        assertThat(WorkingDays.count(MONDAY.plusDays(5), MONDAY.plusDays(6), Set.of())).isZero();
        assertThat(WorkingDays.count(MONDAY.plusDays(5), MONDAY.plusDays(5), Set.of())).isZero();
    }

    @Test
    void aHolidayOnAWorkingDayIsNotCounted() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(4), Set.of(MONDAY.plusDays(2)))).isEqualTo(4);
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(4), Set.of(MONDAY, MONDAY.plusDays(4)))).isEqualTo(3);
    }

    @Test
    void aHolidayOnAWeekendChangesNothingAndAHolidayOutsideTheIntervalIsIgnored() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(6), Set.of(MONDAY.plusDays(5)))).isEqualTo(5);
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(4), Set.of(MONDAY.plusDays(10)))).isEqualTo(5);
    }

    @Test
    void anIntervalOfOnlyHolidaysHasNoWorkingDay() {
        assertThat(WorkingDays.count(MONDAY, MONDAY.plusDays(1), Set.of(MONDAY, MONDAY.plusDays(1)))).isZero();
    }

    @Test
    void aLeapDayIsAnOrdinaryDay() {
        LocalDate february28 = LocalDate.of(2028, 2, 28); // a Monday
        assertThat(WorkingDays.count(february28, february28.plusDays(4), Set.of())).isEqualTo(5);
    }
}
