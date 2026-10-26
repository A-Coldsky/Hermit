package dev.hermit.reef.conch;

import java.time.Instant;

record AvailabilityRow(
        String conch,
        boolean disabled,
        Instant disabledAt,
        String disabledBy,
        String disabledReason) {
}
