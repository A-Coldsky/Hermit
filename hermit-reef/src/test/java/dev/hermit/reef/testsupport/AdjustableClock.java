package dev.hermit.reef.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class AdjustableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant instant;

    public AdjustableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public AdjustableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new AdjustableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
