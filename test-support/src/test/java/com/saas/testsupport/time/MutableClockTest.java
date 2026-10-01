package com.saas.testsupport.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void timeStandsStillUntilAdvanced() {
        MutableClock clock = MutableClock.at(START);

        assertThat(clock.instant()).isEqualTo(START);
        clock.advance(Duration.ofDays(14));
        assertThat(clock.instant()).isEqualTo(START.plus(Duration.ofDays(14)));
    }

    @Test
    void setInstantJumpsToGivenTime() {
        MutableClock clock = MutableClock.at(START);
        Instant later = START.plus(Duration.ofHours(1));

        assertThat(clock.setInstant(later).instant()).isEqualTo(later);
    }

    @Test
    void zonedCopySharesTheSameTimeSource() {
        MutableClock clock = MutableClock.at(START);
        Clock zoned = clock.withZone(ZoneId.of("Europe/Bucharest"));

        clock.advance(Duration.ofMinutes(15));

        assertThat(zoned.instant()).isEqualTo(clock.instant());
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Europe/Bucharest"));
    }
}
