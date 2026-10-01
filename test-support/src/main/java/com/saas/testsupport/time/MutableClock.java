package com.saas.testsupport.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A {@link Clock} that tests move forward explicitly, so expirations are tested without sleeping. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    public static MutableClock at(Instant start) {
        return new MutableClock(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    public static MutableClock startingNow() {
        return at(Instant.now());
    }

    public MutableClock advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
        return this;
    }

    public MutableClock setInstant(Instant instant) {
        now.set(instant);
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** Shares the same time source; advancing either clock moves both. */
    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }
}
