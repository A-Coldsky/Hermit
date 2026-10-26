package dev.hermit.reef.shell;

import java.time.Instant;

record AvailabilityRow(
        String shell,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {
}
