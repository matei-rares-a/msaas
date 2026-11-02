package com.saas.notification.export;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sync/async decision is driven by measured time, not by a list of operations (REQ-SCALE-03). */
@Tag("REQ-SCALE-03")
class ExportTimingsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void withNoHistoryTheConfiguredDefaultCostPerRowIsUsed() {
        ExportTimings timings = new ExportTimings(10, 0.5, registry);

        assertThat(timings.millisPerRow()).isEqualTo(0.5);
        assertThat(timings.estimate(1000)).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void onceExportsAreMeasuredTheirAverageReplacesTheDefault() {
        ExportTimings timings = new ExportTimings(10, 0.5, registry);

        timings.record(1000, Duration.ofSeconds(10), "inline");

        assertThat(timings.millisPerRow()).isEqualTo(10.0);
        assertThat(timings.estimate(300)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theAverageIsWeightedByRowsSoALargeRunCountsForMoreThanASmallOne() {
        ExportTimings timings = new ExportTimings(10, 0.5, registry);

        timings.record(10, Duration.ofMillis(1000), "inline");
        timings.record(990, Duration.ofMillis(990), "inline");

        assertThat(timings.millisPerRow()).isEqualTo(1.99);
    }

    @Test
    void onlyTheMostRecentRunsInTheWindowCount() {
        ExportTimings timings = new ExportTimings(2, 0.5, registry);

        timings.record(100, Duration.ofSeconds(100), "inline");
        timings.record(100, Duration.ofMillis(100), "inline");
        timings.record(100, Duration.ofMillis(100), "background");

        assertThat(timings.millisPerRow()).isEqualTo(1.0);
    }

    @Test
    void anEmptyRunDoesNotSkewTheAverage() {
        ExportTimings timings = new ExportTimings(10, 0.5, registry);

        timings.record(0, Duration.ofSeconds(5), "inline");

        assertThat(timings.millisPerRow()).isEqualTo(0.5);
    }

    @Test
    void everyRunIsPublishedAsATimerTaggedByMode() {
        ExportTimings timings = new ExportTimings(10, 0.5, registry);

        timings.record(10, Duration.ofMillis(20), "inline");
        timings.record(10, Duration.ofMillis(30), "inline");
        timings.record(10, Duration.ofMillis(40), "background");

        Timer inline = registry.get("activity.export.duration").tag("mode", "inline").timer();
        Timer background = registry.get("activity.export.duration").tag("mode", "background").timer();
        assertThat(inline.count()).isEqualTo(2);
        assertThat(inline.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(50.0);
        assertThat(background.count()).isEqualTo(1);
    }

    @Test
    void aWindowOfZeroIsRefused() {
        assertThatThrownBy(() -> new ExportTimings(0, 0.5, registry)).isInstanceOf(IllegalArgumentException.class);
    }
}
