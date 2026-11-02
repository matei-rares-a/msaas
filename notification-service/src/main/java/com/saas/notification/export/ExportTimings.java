package com.saas.notification.export;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What an export has cost so far, used to decide whether the next one runs inline or in the background
 * (REQ-SCALE-03). There is no fixed list of "slow" operations: each finished export (inline or
 * background) records its row count and wall time, and the estimate for a new one is its row count times
 * the average cost per row over the last {@code window} runs. Until there is any history, a configured
 * default cost per row is used. Every run is also published as the {@code activity.export.duration}
 * timer, tagged by mode.
 */
public class ExportTimings {

    private record Sample(long rows, long millis) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final int window;
    private final double defaultMillisPerRow;
    private final MeterRegistry meterRegistry;

    public ExportTimings(int window, double defaultMillisPerRow, MeterRegistry meterRegistry) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be at least 1");
        }
        this.window = window;
        this.defaultMillisPerRow = defaultMillisPerRow;
        this.meterRegistry = meterRegistry;
    }

    public synchronized void record(long rows, Duration elapsed, String mode) {
        if (rows > 0) {
            samples.addLast(new Sample(rows, elapsed.toMillis()));
            while (samples.size() > window) {
                samples.removeFirst();
            }
        }
        Timer.builder("activity.export.duration").tag("mode", mode).register(meterRegistry).record(elapsed);
    }

    /** Average milliseconds per row over the recorded runs, or the configured default when there are none. */
    public synchronized double millisPerRow() {
        long rows = samples.stream().mapToLong(Sample::rows).sum();
        if (rows == 0) {
            return defaultMillisPerRow;
        }
        return (double) samples.stream().mapToLong(Sample::millis).sum() / rows;
    }

    public Duration estimate(long rows) {
        return Duration.ofNanos((long) (millisPerRow() * rows * 1_000_000));
    }
}
